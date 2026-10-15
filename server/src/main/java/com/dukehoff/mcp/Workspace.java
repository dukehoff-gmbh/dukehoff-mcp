/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectManager;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.Sources;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.modules.gradle.spi.GradleSettings;
import org.netbeans.modules.parsing.api.indexing.IndexingManager;
import org.netbeans.spi.project.ActionProgress;
import org.netbeans.spi.project.ActionProvider;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.modules.Places;
import org.openide.util.Lookup;
import org.openide.util.RequestProcessor;
import org.openide.util.lookup.Lookups;

/**
 * The folder the MCP server works on. Opens the project(s) found there the way
 * the Java LSP server does (priming build, contained projects,
 * {@link OpenProjects}) so that class paths get registered and indexed, then
 * tracks readiness for the tools.
 * <p>
 * Inside an IDE the tools work on the projects the user has open instead ({@link #ofOpenProjects()}).
 */
public final class Workspace {

    private static final Logger LOG = Logger.getLogger(Workspace.class.getName());
    private static final RequestProcessor RP = new RequestProcessor(Workspace.class.getName(), 1);
    private static final long PRIMING_TIMEOUT_MINUTES = 15;
    /**
     * How long a tool call waits for the workspace before reporting that it is not ready: below the
     * clients' request timeouts (Copilot CLI: 3 minutes), so the agent gets "retry" instead of a timeout
     * that tells it nothing. Indexing goes on meanwhile. Set with {@code -J-Ddukehoffmcp.wait.seconds=<n>}.
     */
    public static final int WAIT_SECONDS = Integer.getInteger("dukehoffmcp.wait.seconds", 150);
    /** Written to the cache directory once the index is complete, so the next start knows it is warm. */
    private static final String INDEXED_MARKER = "dukehoff-mcp/indexed";

    /** The folder; null for all the projects open in the IDE. */
    private final File root;
    /** The projects come from the IDE (they are open there), not from opening the folder. */
    private final boolean ide;
    /** Inside another NetBeans (the VS Code extension's): leave its global settings alone. */
    private final boolean embedded;
    /** Projects opened (before the initial scan). */
    private final CompletableFuture<List<Project>> opened = new CompletableFuture<>();
    /** The initial scan finished. */
    private final CompletableFuture<Void> scanned = new CompletableFuture<>();
    private volatile String phase = "starting";

    public Workspace(File root) {
        this(root, false);
    }

    /**
     * @param embedded the server runs inside a NetBeans it does not own (the Apache NetBeans
     *        extension for VS Code): its user's settings, such as Gradle trust, are not changed
     */
    public Workspace(File root, boolean embedded) {
        this.root = FileUtil.normalizeFile(root);
        this.embedded = embedded;
        this.ide = false;
    }

    /**
     * The projects open in the IDE the server runs in: nothing is opened or primed, the source roots are
     * those of the open projects at each call, and paths are shown in full (there is no one root folder).
     */
    public static Workspace ofOpenProjects() {
        return ofOpenProjects(null);
    }

    /**
     * The projects open in the IDE that lie in {@code folder} (or contain it): the folder an agent works
     * in, which it names files relative to and which bounds where tools may write, as its harness does.
     * Nothing is opened or primed; null stands for all open projects, shown with full paths.
     */
    public static Workspace ofOpenProjects(File folder) {
        Workspace ws = new Workspace(true, folder == null ? null : FileUtil.normalizeFile(folder));
        ws.opened.complete(List.of());
        ws.scanned.complete(null);
        ws.phase = "ready";
        return ws;
    }

    private Workspace(boolean ide, File folder) {
        this.root = folder;
        this.embedded = true;
        this.ide = ide;
    }

    /** The open projects this workspace covers: in its folder, or containing it; all without a folder. */
    private List<Project> openProjectsHere(Project[] open) {
        List<Project> here = new ArrayList<>();
        for (Project p : open) {
            File dir = FileUtil.toFile(p.getProjectDirectory());
            if (root == null || dir != null && (dir.toPath().startsWith(root.toPath()) || root.toPath().startsWith(dir.toPath()))) {
                here.add(p);
            }
        }
        return here;
    }

    /** The workspace folder, or null for the projects open in the IDE. */
    public File getRoot() {
        return root;
    }

    /** What this workspace covers, for status output. */
    public String describe() {
        return root == null ? "the projects open in the IDE"
                : ide ? root.getPath() + " (the projects open in the IDE there)" : root.getPath();
    }

    /**
     * The folder a build of the project may start from: the workspace folder, or in the IDE the outermost
     * open project's folder containing the project (a Maven reactor opened with its modules).
     */
    public File buildRoot(Project p) {
        if (root != null) {
            return root;
        }
        FileObject dir = p.getProjectDirectory();
        FileObject outer = dir;
        for (Project open : OpenProjects.getDefault().getOpenProjects()) {
            FileObject d = open.getProjectDirectory();
            if (FileUtil.isParentOf(d, outer)) {
                outer = d;
            }
        }
        return FileUtil.toFile(outer);
    }

    /** Human-readable state: starting, opening projects, indexing, ready or failed. */
    public String getPhase() {
        return phase;
    }

    /**
     * Waits until the workspace is ready or failed, at most {@code seconds}.
     *
     * @return whether it is ready
     */
    public boolean awaitReady(long seconds) throws InterruptedException {
        try {
            scanned.get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException | TimeoutException ex) {
            return false;
        }
    }

    public boolean isIndexing() {
        return IndexingManager.getDefault().isIndexing();
    }

    /** Projects opened for this workspace, or an empty list while still opening. */
    public List<Project> getProjects() {
        if (ide) {
            return openProjectsHere(OpenProjects.getDefault().getOpenProjects());
        }
        return opened.isDone() && !opened.isCompletedExceptionally() ? opened.join() : List.of();
    }

    public void openAsync() {
        // in the caller's lookup: inside the VS Code extension's NetBeans, open projects belong to the
        // LSP client's context (for our own runtime this is the default lookup anyway)
        Lookup context = Lookup.getDefault();
        RP.post(() -> Lookups.executeWith(context, this::open));
    }

    private void open() {
        try {
            FileObject dir = FileUtil.toFileObject(root);
            if (dir == null || !dir.isFolder()) {
                throw new IOException("Workspace folder does not exist: " + root);
            }
            if (!embedded) {
                trustGradleBuilds();
            }
            phase = "looking for projects";
            Set<Project> projects = new LinkedHashSet<>();
            collectProjects(dir, projects);
            if (projects.isEmpty()) {
                throw new IOException("No project (Maven, Gradle, Ant) found in " + root);
            }
            phase = "priming projects (resolving dependencies)";
            prime(projects);
            phase = "opening projects";
            OpenProjects.getDefault().open(projects.toArray(Project[]::new), false);
            OpenProjects.getDefault().openProjects().get();
            for (Project p : projects) {
                // initializes source groups and FileOwnerQuery, like the LSP server does
                ProjectUtils.getSources(p).getSourceGroups(Sources.TYPE_GENERIC);
            }
            File marker = Places.getCacheSubfile(INDEXED_MARKER);
            boolean warmStart = marker.isFile();
            opened.complete(List.copyOf(projects));
            phase = warmStart ? "checking the index of the previous run for changes" : "indexing";
            List<FileObject> roots = javaSourceRoots(projects);
            waitForScan(roots, 0);
            // more roots or class paths may register after that scan (large Maven workspaces do it in stages)
            for (int round = 0; round < 3; round++) {
                waitUntilIndexingQuiet();
                List<FileObject> now = javaSourceRoots(projects);
                if (now.equals(roots)) {
                    break;
                }
                LOG.log(Level.INFO, "dukehoff-mcp: source roots changed while indexing ({0} -> {1}), waiting again",
                        new Object[]{roots.size(), now.size()});
                roots = now;
                waitForScan(roots, 0);
            }
            if (!marker.isFile()) {
                marker.createNewFile();
            }
            scanned.complete(null);
            phase = "ready";
            LOG.log(Level.INFO, "MCP workspace {0} ready: {1} projects, {2} Java source roots",
                    new Object[]{root, projects.size(), roots.size()});
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Cannot open MCP workspace " + root, t);
            phase = "failed: " + t.getMessage();
            opened.completeExceptionally(t);
            scanned.completeExceptionally(t);
        }
    }

    private static void collectProjects(FileObject dir, Set<Project> into) throws IOException {
        collectProjects(dir, into, 0);
    }

    /**
     * The workspace's project with its modules, or, if the folder is no project, the projects in its
     * subfolders down to {@link #PROJECT_SEARCH_DEPTH} levels (a folder of checkouts, or a repository
     * whose build sits in a subfolder such as modules/backend). Below a project nothing is searched:
     * its modules come from the project.
     */
    private static void collectProjects(FileObject dir, Set<Project> into, int depth) throws IOException {
        Project p = ProjectManager.getDefault().findProject(dir);
        if (p != null) {
            into.add(p);
            Set<Project> contained = ProjectUtils.getContainedProjects(p, true);
            if (contained != null) {
                into.addAll(contained);
            }
            return;
        }
        if (depth >= PROJECT_SEARCH_DEPTH) {
            return;
        }
        for (FileObject child : dir.getChildren()) {
            if (child.isFolder() && !child.getNameExt().startsWith(".") && !NOT_SEARCHED.contains(child.getNameExt())) {
                collectProjects(child, into, depth + 1);
            }
        }
    }

    private static final int PROJECT_SEARCH_DEPTH = 3;
    /** Build output and dependency folders: never contain the workspace's own projects. */
    private static final Set<String> NOT_SEARCHED = Set.of("target", "build", "out", "bin", "node_modules", "dist");

    /** Runs the priming build (e.g. Maven dependency download) where a project offers one. */
    /**
     * NetBeans runs a Gradle project's build scripts, which it needs to learn the source sets, only
     * for projects the user trusts, and asks in the IDE. Headless nobody can answer: the project then
     * has no source roots and nothing is logged (found at the first user site). The workspace is what
     * the user started this server on, so run its builds as {@code gradlew} would. Every workspace has
     * its own userdir, so the setting stays with this workspace.
     */
    private static void trustGradleBuilds() {
        GradleSettings.getDefault().setGradleExecutionRule(GradleSettings.GradleExecutionRule.ALWAYS);
    }

    private static void prime(Set<Project> projects) {
        List<CompletableFuture<Void>> builds = new ArrayList<>();
        for (Project p : projects) {
            ActionProvider ap = p.getLookup().lookup(ActionProvider.class);
            if (ap == null
                    || !Arrays.asList(ap.getSupportedActions()).contains(ActionProvider.COMMAND_PRIME)
                    || !ap.isActionEnabled(ActionProvider.COMMAND_PRIME, Lookup.EMPTY)) {
                continue;
            }
            CompletableFuture<Void> done = new CompletableFuture<>();
            builds.add(done);
            ap.invokeAction(ActionProvider.COMMAND_PRIME, Lookups.fixed(new ActionProgress() {
                @Override
                protected void started() {
                }

                @Override
                public void finished(boolean success) {
                    if (!success) {
                        LOG.log(Level.WARNING, "Priming build failed for {0}", p.getProjectDirectory());
                    }
                    done.complete(null);
                }
            }));
        }
        try {
            CompletableFuture.allOf(builds.toArray(CompletableFuture[]::new))
                    .get(PRIMING_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException | ExecutionException | TimeoutException ex) {
            LOG.log(Level.WARNING, "Priming did not finish, continuing anyway", ex);
        }
    }

    /**
     * The scan waited for at startup covers the roots and class paths registered when it started. Scans
     * that start after it are not covered: GlassFish (584 Maven modules) reported ready after 2 minutes
     * and find_usages then found 60 of 73 files, 70 when asked after the indexing had settled. So wait
     * until NetBeans has not been indexing for {@link #QUIET_SECONDS} in a row.
     */
    private static void waitUntilIndexingQuiet() throws InterruptedException {
        long quietSince = -1;
        while (true) {
            long now = System.currentTimeMillis();
            if (IndexingManager.getDefault().isIndexing()) {
                quietSince = -1;
            } else if (quietSince < 0) {
                quietSince = now;
            } else if (now - quietSince >= TimeUnit.SECONDS.toMillis(QUIET_SECONDS)) {
                return;
            }
            Thread.sleep(250);
        }
    }

    private static final long QUIET_SECONDS = 5;

    /**
     * Waits until the scan of the roots is finished.
     *
     * @param timeoutMillis maximum wait, 0 for no limit
     */
    private static void waitForScan(List<FileObject> roots, long timeoutMillis)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        if (roots.isEmpty()) {
            return;
        }
        JavaSource js = JavaSource.create(ClasspathInfo.create(roots.get(0)));
        if (js != null) {
            Future<Void> done = js.runWhenScanFinished(cc -> { }, true);
            if (timeoutMillis > 0) {
                done.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                done.get();
            }
        }
    }

    public static List<FileObject> javaSourceRoots(Iterable<Project> projects) {
        Set<FileObject> roots = new LinkedHashSet<>();
        for (Project p : projects) {
            for (SourceGroup g : ProjectUtils.getSources(p).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)) {
                roots.add(g.getRootFolder());
            }
        }
        return List.copyOf(roots);
    }

    /**
     * Waits until the workspace is open and indexed, picks up changes made on
     * disk by the agent, and returns the Java source roots. Waits up to
     * {@link #WAIT_SECONDS} seconds.
     * <p>
     * After a restart, NetBeans checks the index of the previous run for changes
     * before the initial scan ends. Queries during that check can miss results
     * (find_implementations found 9 of 32 subtypes, find_symbol none of an
     * existing type), so every call waits for it.
     *
     * @throws NotReadyException if the workspace is not ready in time
     */
    public List<FileObject> awaitSourceRoots() throws NotReadyException {
        if (ide) {
            return awaitOpenProjectRoots();
        }
        long start = System.currentTimeMillis();
        long deadline = start + TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
        boolean startingUp = !scanned.isDone();
        List<Project> projects;
        try {
            projects = opened.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw notReady();
        } catch (InterruptedException | ExecutionException ex) {
            throw new NotReadyException("The workspace could not be opened: " + phase);
        }
        List<FileObject> roots = javaSourceRoots(projects);
        // the agent edits files directly on disk; make NetBeans notice before answering
        long[] phases = new long[4]; // ends of: open, refresh, initial scan, rescan
        phases[0] = System.currentTimeMillis();
        FileUtil.refreshFor(root);
        phases[1] = System.currentTimeMillis();
        try {
            scanned.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            phases[2] = System.currentTimeMillis();
            if (startingUp) {
                // for eval/metrics.py: time a tool call spent waiting for the initial scan
                LOG.log(Level.INFO, "dukehoff-mcp timing: startup wait {0} {1}",
                        new Object[]{Long.toString(start), Long.toString(System.currentTimeMillis())});
            }
            // the refresh above may have started another scan
            waitForScan(roots, Math.max(1, deadline - System.currentTimeMillis()));
            phases[3] = System.currentTimeMillis();
        } catch (TimeoutException ex) {
            logWait(start, phases);
            throw notReady();
        } catch (IOException | InterruptedException | ExecutionException ex) {
            LOG.log(Level.INFO, "Waiting for scan failed", ex);
        }
        logWait(start, phases);
        return roots;
    }

    /** {@link #awaitSourceRoots()} inside the IDE: the open projects, refreshed and scanned. */
    private List<FileObject> awaitOpenProjectRoots() throws NotReadyException {
        long start = System.currentTimeMillis();
        long deadline = start + TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
        long[] phases = new long[4];
        Project[] open;
        try {
            // completes once the projects of the last session are open again
            open = OpenProjects.getDefault().openProjects().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw notReady();
        } catch (InterruptedException | ExecutionException ex) {
            throw new NotReadyException("The IDE's projects could not be opened: " + ex.getMessage());
        }
        List<Project> here = openProjectsHere(open);
        if (here.isEmpty()) {
            throw new NotReadyException(root == null
                    ? "No project is open in the IDE. Open the project (Maven, Gradle, Ant) and call this tool again."
                    : "No project in " + root + " is open in the IDE. Open it there (File > Open Project; Maven, "
                    + "Gradle or Ant) and call this tool again.");
        }
        phases[0] = System.currentTimeMillis();
        List<File> dirs = new ArrayList<>();
        if (root != null) {
            dirs.add(root);
        }
        for (Project p : here) {
            File dir = FileUtil.toFile(p.getProjectDirectory());
            if (dir != null) {
                dirs.add(dir);
            }
        }
        FileUtil.refreshFor(dirs.toArray(File[]::new));
        phases[1] = System.currentTimeMillis();
        phases[2] = phases[1];
        List<FileObject> roots = javaSourceRoots(here);
        try {
            waitForScan(roots, Math.max(1, deadline - System.currentTimeMillis()));
            phases[3] = System.currentTimeMillis();
        } catch (TimeoutException ex) {
            logWait(start, phases);
            throw notReady();
        } catch (IOException | InterruptedException | ExecutionException ex) {
            LOG.log(Level.INFO, "Waiting for scan failed", ex);
        }
        logWait(start, phases);
        return roots;
    }

    /** Logs where a wait of more than a second went; a phase not reached is "-". */
    private void logWait(long start, long[] phases) {
        long now = System.currentTimeMillis();
        if (now - start < 1000) {
            return;
        }
        String[] names = {"open", "refresh", "initial scan", "rescan"};
        StringBuilder sb = new StringBuilder("dukehoff-mcp wait: ").append(now - start).append(" ms (");
        long previous = start;
        for (int i = 0; i < phases.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(names[i]).append(' ')
                    .append(phases[i] == 0 ? "-" : (phases[i] - previous) + " ms");
            if (phases[i] != 0) {
                previous = phases[i];
            }
        }
        LOG.info(sb.append("); workspace: ").append(phase).toString());
    }

    private NotReadyException notReady() {
        return new NotReadyException("The workspace is not ready yet (" + phase + ") after waiting "
                + WAIT_SECONDS + " s. This continues in the background: call this tool again, it picks up "
                + "where it stopped. The first start of a large workspace indexes everything; later starts "
                + "are faster." + (Offered.has("workspace_status") ? " workspace_status shows the progress." : ""));
    }

    /**
     * Finds a file given relative to the workspace root (in the IDE: to an open project's folder) or as
     * an absolute path.
     *
     * @return the file, or {@code null} if it does not exist
     */
    public FileObject findFile(String path) {
        File f = new File(path);
        if (f.isAbsolute()) {
            return FileUtil.toFileObject(FileUtil.normalizeFile(f));
        }
        if (root != null) {
            return FileUtil.toFileObject(FileUtil.normalizeFile(new File(root, path)));
        }
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            File dir = FileUtil.toFile(p.getProjectDirectory());
            FileObject fo = dir == null ? null : FileUtil.toFileObject(FileUtil.normalizeFile(new File(dir, path)));
            if (fo != null) {
                return fo;
            }
        }
        return null;
    }

    /**
     * Where tools may write ({@link WriteBoundary}): the workspace folder (in the IDE: the open projects'
     * folders), the folders the registration allows and the given paths, relative ones resolved like
     * {@link #findFile} resolves them.
     */
    public List<File> writableRoots(List<String> allowed) {
        List<File> roots = new ArrayList<>();
        if (root != null) {
            roots.add(root);
        } else {
            for (Project p : OpenProjects.getDefault().getOpenProjects()) {
                File dir = FileUtil.toFile(p.getProjectDirectory());
                if (dir != null) {
                    roots.add(dir);
                }
            }
        }
        List<String> extra = new ArrayList<>(WriteBoundary.registered());
        extra.addAll(allowed);
        for (String path : extra) {
            roots.add(resolve(path));
        }
        return roots;
    }

    /** A path as given by the agent or the user, also when nothing exists there. */
    private File resolve(String path) {
        File f = new File(path);
        if (!f.isAbsolute()) {
            FileObject existing = findFile(path);
            File base = root;
            if (base == null) {
                Project[] open = OpenProjects.getDefault().getOpenProjects();
                base = open.length == 0 ? null : FileUtil.toFile(open[0].getProjectDirectory());
            }
            f = existing != null && FileUtil.toFile(existing) != null ? FileUtil.toFile(existing) : new File(base, path);
        }
        return FileUtil.normalizeFile(f);
    }

    /**
     * Returns the path of a file relative to the workspace, or its absolute path outside of it (and
     * always in the IDE).
     */
    public String displayPath(FileObject fo) {
        if (root == null) {
            File f = FileUtil.toFile(fo);
            return f != null ? f.getPath() : fo.getPath();
        }
        FileObject rootFo = FileUtil.toFileObject(root);
        if (fo.equals(rootFo)) {
            return ".";
        }
        String rel = rootFo != null ? FileUtil.getRelativePath(rootFo, fo) : null;
        return rel != null ? rel : fo.getPath();
    }

    public static final class NotReadyException extends Exception {

        public NotReadyException(String message) {
            super(message);
        }
    }
}
