/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.netbeans.api.queries.FileEncodingQuery;
import com.dukehoff.mcp.ChangeLog;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.WriteBoundary;
import com.dukehoff.mcp.Tool.Result;
import com.dukehoff.mcp.Workspace;
import org.netbeans.modules.parsing.api.indexing.IndexingManager;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.api.RefactoringElement;
import org.netbeans.modules.refactoring.api.RefactoringSession;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Runs a refactoring for a tool and reports the result the same way for all
 * of them: fatal problems abort before anything changes; otherwise the
 * changes are saved to disk, the touched files are reindexed and the result
 * is warnings plus a unified diff.
 */
final class RefactoringRunner {

    static final int DEFAULT_MAX_DIFF_LINES = 400;
    /**
     * Held while anything writes to the workspace: the persistent server serves several clients, and a
     * refactoring must find the index its predecessor refreshed (stale file names broke the Java plugin).
     */
    static final ReentrantLock WRITES = new ReentrantLock(true);
    /** Tool descriptions say this, so agents do not call diagnostics after every refactoring. */
    static final String COMPILE_CHECK = " The result ends with a compile check: only errors the change introduced, "
            + "also in files it did not touch, so no separate diagnostics call is needed.";

    private final Workspace workspace;
    private final List<FileObject> roots;
    private final AbstractRefactoring refactoring;
    private final String description;
    private final List<FileObject> involvedFiles = new ArrayList<>();
    private Function<FileObject, FileObject> relocate = fo -> null;

    /**
     * @param description what the refactoring does, used in messages,
     *     e.g. "rename com.acme.Foo#bar() to baz"
     */
    RefactoringRunner(Workspace workspace, List<FileObject> roots, AbstractRefactoring refactoring, String description) {
        this.workspace = workspace;
        this.roots = roots;
        this.refactoring = refactoring;
        this.description = description;
    }

    /** The argument with paths outside the workspace a call may change, after the user agreed. */
    static final String ALLOW_OUTSIDE = "allow_outside";
    static final Map<String, Object> ALLOW_OUTSIDE_SCHEMA = Json.obj("type", "array", "items", Json.obj("type", "string"),
            "description", "Files or folders outside the workspace this call may change. Only with the user's consent: "
            + "without it, a call that would change files outside the workspace changes nothing and names them, "
            + "so that you can ask the user first");
    private List<String> allowOutside = List.of();

    /** Takes the paths outside the workspace the call allows ({@value #ALLOW_OUTSIDE}). */
    RefactoringRunner allowingOutside(Map<?, ?> arguments) {
        allowOutside = Json.strings(arguments, ALLOW_OUTSIDE);
        return this;
    }

    /**
     * Why a change of these files is refused: some lie outside where tools may write
     * ({@link WriteBoundary}); null when all may be written.
     */
    static String outsideRefusal(Workspace workspace, Collection<File> files, List<String> allowOutside) {
        List<File> outside = WriteBoundary.outside(files, workspace.writableRoots(allowOutside));
        if (outside.isEmpty()) {
            return null;
        }
        String where = workspace.getRoot() != null ? "the workspace" : "the open projects";
        StringBuilder sb = new StringBuilder("it would also change ").append(outside.size())
                .append(outside.size() == 1 ? " file" : " files").append(" outside ").append(where).append(":\n");
        outside.stream().limit(10).forEach(f -> sb.append("  ").append(f.getPath()).append('\n'));
        if (outside.size() > 10) {
            sb.append("  ... and ").append(outside.size() - 10).append(" more\n");
        }
        // what to allow: the files, or for many their folders
        List<String> allow = (outside.size() <= 10 ? outside.stream() : outside.stream().map(File::getParentFile))
                .map(File::getPath).distinct().limit(10).toList();
        sb.append("Nothing was changed. Files outside ").append(where).append(" are changed only with the user's ")
                .append("consent: ask the user, and if they agree, call again with ").append(ALLOW_OUTSIDE).append(": [")
                .append(String.join(", ", allow.stream().map(p -> "\"" + p.replace("\\", "\\\\") + "\"").toList()))
                .append("]. Folders that may always be changed are set where the server is registered (--allow-writes).");
        return sb.toString();
    }

    /** Files to include in the diff even if no refactoring element points at them. */
    RefactoringRunner involving(FileObject file) {
        involvedFiles.add(file);
        return this;
    }

    /**
     * Where to find a file whose {@link FileObject} became invalid, e.g. the
     * new name of a renamed class. Returning {@code null} reports the file as
     * deleted.
     */
    RefactoringRunner relocatingWith(Function<FileObject, FileObject> relocate) {
        this.relocate = relocate;
        return this;
    }

    /** Path, URL and content of a file before the refactoring. */
    private record Before(String path, URL url, File file, String text) {
    }

    /**
     * A changed file.
     *
     * @param newUrl {@code null} if the file was deleted
     */
    private record Change(String path, String diff, URL oldUrl, URL newUrl, boolean moved, ChangeLog.FileChange file) {
    }

    Result run(int maxDiffLines) throws IOException {
        WRITES.lock();
        try {
            return runLocked(maxDiffLines);
        } finally {
            WRITES.unlock();
        }
    }

    private Result runLocked(int maxDiffLines) throws IOException {
        RefactoringSession session = RefactoringSession.create("MCP " + description);
        try {
            // plugins often report the same problem once per element
            Set<String> warnings = new LinkedHashSet<>();
            Problem blocking = collect(refactoring.preCheck(), warnings);
            if (blocking == null) {
                blocking = collect(refactoring.checkParameters(), warnings);
            }
            if (blocking == null) {
                blocking = collect(refactoring.prepare(session), warnings);
            }
            if (blocking != null) {
                return Result.error("Cannot " + description + ": " + blocking.getMessage()
                        + warningsText(warnings) + "\nNothing was changed.");
            }

            Map<FileObject, Before> before = new LinkedHashMap<>();
            for (FileObject fo : involvedFiles) {
                snapshot(fo, before);
            }
            for (RefactoringElement re : session.getRefactoringElements()) {
                snapshot(re.getParentFile(), before);
            }
            String refusal = outsideRefusal(workspace,
                    before.values().stream().map(Before::file).filter(f -> f != null).toList(), allowOutside);
            if (refusal != null) {
                return Result.error("Cannot " + description + ": " + refusal + warningsText(warnings));
            }
            CompileCheck check = CompileCheck.before(workspace, roots, before.keySet());
            Problem commit = collect(session.doRefactoring(true), warnings);
            List<Change> changes = changes(before);
            // the index learns about the saved files asynchronously; without this, a following
            // call (e.g. renaming a method of a just renamed class) could see stale file names
            reindex(changes);
            // for review in the IDE, also what a failed commit changed: it can be undone there
            ChangeLog.record(description, changes.stream().map(Change::file).toList());
            if (commit != null) {
                return Result.error("Failed to " + description + " while applying the changes: " + commit.getMessage()
                        + warningsText(warnings) + "\nSome files may have been changed; check with git status.");
            }
            if (changes.isEmpty()) {
                return Result.error("Trying to " + description + " changed nothing." + warningsText(warnings)
                        + "\nDetails are in the IDE log (<userdir>/var/log/messages.log).");
            }
            Map<URL, URL> changed = new LinkedHashMap<>();
            for (Change c : changes) {
                changed.put(c.oldUrl(), c.newUrl());
            }
            return Result.ok(report(changes, warnings, maxDiffLines) + check.after(changed));
        } finally {
            session.finished();
        }
    }

    /**
     * Adds non-fatal problems to {@code warnings}; returns the first fatal one.
     * A problem with details (e.g. "references found, show usages") is fatal
     * too: in the IDE the user would have to look at them before going on.
     */
    private static Problem collect(Problem p, Set<String> warnings) {
        Problem blocking = null;
        for (; p != null; p = p.getNext()) {
            if (p.isFatal() || p.getDetails() != null) {
                if (blocking == null) {
                    blocking = p;
                }
            } else {
                warnings.add(p.getMessage());
            }
        }
        return blocking;
    }

    private static String warningsText(Set<String> warnings) {
        StringBuilder sb = new StringBuilder();
        for (String w : warnings) {
            sb.append("\nWarning: ").append(w);
        }
        return sb.toString();
    }

    private void snapshot(FileObject fo, Map<FileObject, Before> into) throws IOException {
        if (fo != null && fo.isData() && !into.containsKey(fo)) {
            into.put(fo, new Before(workspace.displayPath(fo), fo.toURL(), FileUtil.toFile(fo), read(fo)));
        }
    }

    private static String read(FileObject fo) throws IOException {
        return fo.asText(FileEncodingQuery.getEncoding(fo).name());
    }

    private List<Change> changes(Map<FileObject, Before> before) throws IOException {
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<FileObject, Before> e : before.entrySet()) {
            FileObject fo = e.getKey();
            Before b = e.getValue();
            if (!fo.isValid()) {
                fo = relocate.apply(fo);
                if (fo == null || !fo.isValid()) {
                    changes.add(new Change(b.path(), UnifiedDiff.deletion(b.path(), b.text()), b.url(), null, false,
                            new ChangeLog.FileChange(b.file(), null, b.text(), null)));
                    continue;
                }
            }
            String path = workspace.displayPath(fo);
            String text = read(fo);
            String diff = UnifiedDiff.diff(b.path(), path, b.text(), text);
            if (!diff.isEmpty()) {
                changes.add(new Change(path, diff, b.url(), fo.toURL(), !path.equals(b.path()),
                        new ChangeLog.FileChange(b.file(), FileUtil.toFile(fo), b.text(), text)));
            }
        }
        return changes;
    }

    private void reindex(List<Change> changes) {
        Set<URL> files = new LinkedHashSet<>();
        for (Change c : changes) {
            files.add(c.oldUrl());
            if (c.newUrl() != null) {
                files.add(c.newUrl());
            }
        }
        reindex(roots, files);
    }

    /**
     * Reindexes the given files (also deleted ones) and waits until done, so
     * that the next tool call sees the changes.
     */
    static void reindex(List<FileObject> roots, Set<URL> files) {
        for (FileObject root : roots) {
            URL rootUrl = root.toURL();
            String prefix = rootUrl.toString();
            Set<URL> inRoot = new LinkedHashSet<>();
            for (URL u : files) {
                if (u.toString().startsWith(prefix)) {
                    inRoot.add(u);
                }
            }
            if (!inRoot.isEmpty()) {
                IndexingManager.getDefault().refreshIndexAndWait(rootUrl, inRoot);
            }
        }
    }

    private String report(List<Change> changes, Set<String> warnings, int maxDiffLines) {
        long moved = changes.stream().filter(Change::moved).count();
        long deleted = changes.stream().filter(c -> c.newUrl() == null).count();
        StringBuilder sb = new StringBuilder();
        sb.append("Done: ").append(description).append(". ")
                .append(changes.size()).append(changes.size() == 1 ? " file" : " files").append(" changed");
        if (moved > 0) {
            sb.append(", ").append(moved).append(moved == 1 ? " file" : " files").append(" renamed or moved");
        }
        if (deleted > 0) {
            sb.append(", ").append(deleted).append(deleted == 1 ? " file" : " files").append(" deleted");
        }
        sb.append(". The changes are saved to disk.\n");
        for (String w : warnings) {
            sb.append("Warning: ").append(w).append('\n');
        }
        sb.append('\n');
        int lines = 0;
        List<String> omitted = new ArrayList<>();
        for (Change c : changes) {
            int n = c.diff().split("\n", -1).length - 1;
            if (lines + n > maxDiffLines && lines > 0) {
                omitted.add(c.path());
                continue;
            }
            sb.append(c.diff());
            lines += n;
        }
        if (!omitted.isEmpty()) {
            sb.append("\n... diff omitted for ").append(omitted.size()).append(omitted.size() == 1 ? " file" : " files")
                    .append(" (raise 'max_diff_lines' or use git diff): ").append(String.join(", ", omitted)).append('\n');
        }
        return sb.toString();
    }
}
