/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Tool;
import com.dukehoff.mcp.Workspace;
import org.netbeans.api.java.queries.UnitTestForSourceQuery;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.SourceUtils;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.modules.gradle.api.GradleBaseProject;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.URLMapper;

/**
 * Runs a test under the debugger, stops once (method entry, line, or a thrown exception) and
 * returns what an agent would otherwise get with println, rerun and cleanup: where it stopped, the
 * project's stack, the local variables of the top frames and the values of given expressions.
 * One call, no session: the test JVM ends afterwards.
 */
public final class DebugTool implements Tool {

    private static final int DEFAULT_TIMEOUT = 120;
    private static final Pattern LINE = Pattern.compile("(.+):(\\d+)");
    private static final Pattern TEST_CLASS = Pattern.compile("Test\\w*|\\w*(Test|Tests|TestCase|IT)");

    private final Workspace workspace;

    public DebugTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "debug";
    }

    @Override
    public String description() {
        return "Run a JUnit test under the debugger and stop once, then get the values at that point: where it "
                + "stopped (with the source line), the stack in the project's code, the local variables of the top "
                + "frames, and the values of 'expressions'. One call instead of adding println, rerunning and "
                + "removing it again; use it when the cause of a bug depends on runtime values. Stop at 'stop_at' "
                + "(a method: com.acme.Foo#bar or Foo#bar(String); or a line: src/main/java/com/acme/Foo.java:42 or "
                + "com.acme.Foo:42) or where an 'exception' is thrown (e.g. NullPointerException, subclasses too). "
                + "'test' (com.acme.FooTest or FooTest#saves) is what runs; without it, the tests that use the class "
                + "of 'stop_at'. 'when' stops only where a condition holds (srcLen == length, order.total() > 1000); "
                + "'expressions' are paths in the stopped frame: order, order.lines, order.total(), this.count. "
                + "The test JVM is ended afterwards; nothing changes on disk.";
    }

    @Override
    public List<String> searchTerms() {
        return List.of("debugger", "breakpoint", "debug test", "inspect variables", "stack trace", "exception breakpoint",
                "runtime values");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "stop_at", Json.obj("type", "string",
                                "description", "Method (com.acme.Foo#bar, Foo#bar(String)) or line (src/main/java/com/acme/Foo.java:42, com.acme.Foo:42)"),
                        "exception", Json.obj("type", "string",
                                "description", "Stop where an exception of this type (or a subclass) is thrown, e.g. NullPointerException"),
                        "test", Json.obj("type", "string",
                                "description", "Test class or method to run: com.acme.FooTest, FooTest#saves; default: the tests using the class of stop_at"),
                        "expressions", Json.obj("type", "array", "items", Json.obj("type", "string"),
                                "description", "Values to show in the stopped frame: order, order.lines, order.total(), this.count"),
                        "when", Json.obj("type", "string",
                                "description", "Stop only where this holds in the stopped frame: srcLen == length, order.total() > 1000, name != null, customer.id().equals(\"c-17\"); one comparison, no && or ||"),
                        "hit", Json.obj("type", "integer", "description", "Stop at the n-th hit (where 'when' holds), default 1"),
                        "timeout", Json.obj("type", "integer",
                                "description", "Seconds until giving up, default " + DEFAULT_TIMEOUT + ", at most " + Workspace.WAIT_SECONDS)));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String stopAt = Json.string(arguments, "stop_at");
        String exception = Json.string(arguments, "exception");
        String test = Json.string(arguments, "test");
        List<String> expressions = new ArrayList<>();
        if (arguments.get("expressions") instanceof List<?> list) {
            list.stream().filter(o -> o instanceof String s && !s.isBlank()).forEach(o -> expressions.add(((String) o).strip()));
        }
        int hit = Json.integer(arguments, "hit", 1);
        String when = Json.string(arguments, "when");
        int timeout = Math.max(5, Math.min(Workspace.WAIT_SECONDS, Json.integer(arguments, "timeout", DEFAULT_TIMEOUT)));
        if ((stopAt == null) == (exception == null)) {
            return Result.error("Give either 'stop_at' (a method or a line) or 'exception'.");
        }
        if (exception != null && test == null) {
            return Result.error("With 'exception', give the 'test' to run.");
        }
        List<FileObject> roots;
        try {
            roots = workspace.awaitSourceRoots();
        } catch (Workspace.NotReadyException ex) {
            return Result.error(ex.getMessage());
        }

        // where to stop, and the source file of its class
        DebugRun.StopAt stop;
        FileObject stopFile = null;
        String shown;
        if (exception != null) {
            stop = DebugRun.StopAt.exception(exception, hit, when);
            shown = "where " + exception + " is thrown";
        } else {
            Matcher m = LINE.matcher(stopAt);
            if (m.matches() && !stopAt.contains("#")) {
                int line = Integer.parseInt(m.group(2));
                stopFile = workspace.findFile(m.group(1));
                if (stopFile == null || !stopFile.isData()) {
                    stopFile = resolveType(m.group(1), roots);
                }
                if (stopFile == null) {
                    return Result.error("'" + m.group(1) + "' is neither a file nor a class in the workspace sources.");
                }
                String cls = topLevelClass(stopFile, roots);
                if (cls == null) {
                    return Result.error(workspace.displayPath(stopFile) + " is not in a Java source root.");
                }
                stop = DebugRun.StopAt.line(cls, line, hit, when);
                shown = "at " + workspace.displayPath(stopFile) + ":" + line;
            } else {
                SymbolSpec spec;
                try {
                    spec = SymbolSpec.parse(stopAt);
                } catch (IllegalArgumentException ex) {
                    return Result.error(ex.getMessage());
                }
                if (spec.member() == null) {
                    return Result.error("'stop_at' needs a method (com.acme.Foo#bar) or a line (com.acme.Foo:42), not a type.");
                }
                SymbolResolver.Resolved r;
                try {
                    r = SymbolResolver.resolve(spec, roots);
                } catch (SymbolResolver.ResolutionException ex) {
                    return Result.error(ex.getMessage());
                }
                if (r.kind() != ElementKind.METHOD && r.kind() != ElementKind.CONSTRUCTOR) {
                    return Result.error(r.signature() + " is not a method or constructor; stop at a line instead.");
                }
                stopFile = r.file();
                String sig = r.signature();
                String owner = sig.substring(0, sig.indexOf('#'));
                String params = sig.substring(sig.indexOf('(') + 1, sig.length() - 1);
                stop = DebugRun.StopAt.method(binaryName(owner, stopFile, roots), r.kind() == ElementKind.CONSTRUCTOR ? "<init>" : r.simpleName(),
                        params.isEmpty() ? List.of() : List.of(params.split(",")), hit, when);
                shown = "in " + sig;
            }
        }

        // what runs
        Map<String, FileObject> tests = new LinkedHashMap<>();
        if (test != null) {
            String cls = test.contains("#") ? test.substring(0, test.indexOf('#')) : test;
            String method = test.contains("#") ? test.substring(test.indexOf('#')) : "";
            FileObject file = SymbolResolver.findSourceFile(cls, roots);
            if (file == null) {
                try {
                    cls = SymbolResolver.qualify(cls, roots);
                } catch (SymbolResolver.ResolutionException ex) {
                    return Result.error("No test class " + cls + " in the workspace sources. " + ex.getMessage());
                }
                file = SymbolResolver.findSourceFile(cls, roots);
            }
            tests.put(cls + method, file);
        } else {
            tests.putAll(testsUsing(stopFile, roots));
            if (tests.isEmpty()) {
                return Result.error("No test class uses " + workspace.displayPath(stopFile) + ". Give the 'test' to run.");
            }
        }
        FileObject firstTest = tests.values().iterator().next();
        Project project = FileOwnerQuery.getOwner(firstTest);
        TestCommand command = project == null ? null
                : command(project, workspace.getRoot(), List.copyOf(new TreeSet<>(tests.keySet())), firstTest);
        if (command == null) {
            return Result.error("The test's project is neither Maven, Gradle nor a NetBeans module; debug supports those.");
        }
        TestCommand.DebugCommand debug = command.debugging(freePort());

        long started = System.currentTimeMillis();
        DebugRun.Snapshot s = DebugRun.run(debug, stop, expressions, timeout * 1000L, projectClass(roots));
        long seconds = (System.currentTimeMillis() - started) / 1000;
        String ran = tests.size() <= 3 ? String.join(", ", tests.keySet())
                : tests.size() + " test classes using " + (stopFile == null ? "it" : stopFile.getName());
        if (s.frames().isEmpty()) {
            return Result.error("Did not stop " + shown + ": " + s.reason() + " (" + seconds + " s, ran " + ran + ").\n"
                    + outcome(s.output()) + "Command: " + debug.command().display() + "\n");
        }
        return Result.ok(format(s, roots, ran, seconds));
    }

    /** The snapshot: where, the source line, project frames with locals, expressions. */
    private String format(DebugRun.Snapshot s, List<FileObject> roots, String ran, long seconds) {
        StringBuilder sb = new StringBuilder("Stopped: ").append(s.reason()).append(" (thread ").append(s.thread()).append(")\n");
        DebugRun.Frame top = s.frames().stream().filter(DebugRun.Frame::project).findFirst().orElse(s.frames().get(0));
        String line = sourceLine(top, roots);
        if (line != null) {
            sb.append("  ").append(top.line()).append(": ").append(line).append('\n');
        }
        sb.append("\nStack (project frames, innermost first):\n");
        int omitted = 0;
        for (DebugRun.Frame f : s.frames()) {
            if (!f.project()) {
                omitted++;
                continue;
            }
            if (omitted > 0 && f != top) {
                sb.append("  ... ").append(omitted).append(omitted == 1 ? " frame" : " frames").append(" outside the project\n");
            }
            omitted = 0;
            sb.append("  ").append(f.method()).append(" (").append(f.file()).append(':').append(f.line()).append(")\n");
            for (Map.Entry<String, String> v : f.locals().entrySet()) {
                sb.append("      ").append(v.getKey()).append(" = ").append(v.getValue()).append('\n');
            }
        }
        if (!s.expressions().isEmpty()) {
            sb.append("\nExpressions (in ").append(top.method()).append("):\n");
            for (Map.Entry<String, String> e : s.expressions().entrySet()) {
                sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        return sb.append("\nRan ").append(ran).append(" under the debugger; ").append(seconds)
                .append(" s. The test JVM was ended after the snapshot.\n").toString();
    }

    private static String sourceLine(DebugRun.Frame f, List<FileObject> roots) {
        String cls = f.method().substring(0, f.method().lastIndexOf('.'));
        FileObject file = SymbolResolver.findSourceFile(cls.contains("$") ? cls.substring(0, cls.indexOf('$')) : cls, roots);
        if (file == null || f.line() <= 0) {
            return null;
        }
        List<String> lines = WhereUsed.readLines(file);
        return f.line() <= lines.size() ? lines.get(f.line() - 1).strip() : null;
    }

    /** The lines of a test run that say how it went, when it did not stop. */
    private static String outcome(String output) {
        if (output == null) {
            return "";
        }
        String lines = output.lines().filter(l -> l.contains("Tests run:") || l.contains("FAIL") || l.contains("[ERROR]")
                || l.contains("BUILD") || l.contains(": error:")).limit(15).collect(Collectors.joining("\n"));
        return lines.isEmpty() ? "" : lines + "\n";
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static FileObject resolveType(String name, List<FileObject> roots) {
        // a nested class (com.acme.Outer.Inner or the binary com.acme.Outer$Inner) is in its top-level class's file
        String type = name.replace('$', '.');
        while (true) {
            FileObject f = SymbolResolver.findSourceFile(type, roots);
            if (f == null) {
                try {
                    f = SymbolResolver.findSourceFile(SymbolResolver.qualify(type, roots), roots);
                } catch (SymbolResolver.ResolutionException ex) {
                    f = null;
                }
            }
            if (f != null || type.lastIndexOf('.') < 0) {
                return f;
            }
            type = type.substring(0, type.lastIndexOf('.'));
        }
    }

    /**
     * The binary name of a class given by its canonical name (com.acme.Outer.Inner -> com.acme.Outer$Inner),
     * from the top-level class of the file that declares it.
     */
    static String binaryName(String canonical, FileObject declaringFile, List<FileObject> roots) {
        FileObject file = declaringFile != null ? declaringFile : SymbolResolver.findSourceFile(canonical, roots);
        String top = file == null ? null : topLevelClass(file, roots);
        return binaryName(canonical, top);
    }

    /** {@code canonical} with the parts after the top-level class {@code top} joined by '$'. */
    static String binaryName(String canonical, String top) {
        return top == null || !canonical.startsWith(top + ".") ? canonical : top + canonical.substring(top.length()).replace('.', '$');
    }

    /** The top-level class a source file declares, by its path in its root. */
    private static String topLevelClass(FileObject file, List<FileObject> roots) {
        Path real = realPath(file);
        for (FileObject r : roots) {
            Path root = realPath(r);
            if (real != null && root != null && real.startsWith(root)) {
                return root.relativize(real).toString().replace(File.separatorChar, '/').replaceFirst("\\.java$", "").replace('/', '.');
            }
        }
        return null;
    }

    /** Test classes (in the test roots of the class's project) that reference the class of a source file. */
    private static Map<String, FileObject> testsUsing(FileObject file, List<FileObject> roots) {
        Map<String, FileObject> tests = new LinkedHashMap<>();
        String fqn = topLevelClass(file, roots);
        if (fqn == null) {
            return tests;
        }
        if (TEST_CLASS.matcher(file.getName()).matches()) {
            tests.put(fqn, file);
            return tests;
        }
        FileObject root = roots.stream().filter(r -> realPath(r) != null && realPath(file).startsWith(realPath(r))).findFirst().orElse(null);
        for (FileObject testRoot : testRoots(root, roots)) {
            ClasspathInfo cpInfo = ClasspathInfo.create(testRoot);
            Set<ElementHandle<TypeElement>> refs = cpInfo.getClassIndex().getElements(
                    ElementHandle.createTypeElementHandle(ElementKind.CLASS, fqn),
                    EnumSet.of(ClassIndex.SearchKind.TYPE_REFERENCES), EnumSet.of(ClassIndex.SearchScope.SOURCE));
            for (ElementHandle<TypeElement> h : refs == null ? Set.<ElementHandle<TypeElement>>of() : refs) {
                FileObject src = SourceUtils.getFile(h, cpInfo);
                if (src != null && FileUtil.isParentOf(testRoot, src) && TEST_CLASS.matcher(src.getName()).matches()) {
                    tests.put(FileUtil.getRelativePath(testRoot, src).replaceFirst("\\.java$", "").replace('/', '.'), src);
                }
            }
        }
        return tests;
    }

    /** As the project says (Maven), or else the project's other source roots (Gradle does not answer the query). */
    private static List<FileObject> testRoots(FileObject root, List<FileObject> roots) {
        List<FileObject> tests = new ArrayList<>();
        if (root == null) {
            return tests;
        }
        for (URL url : UnitTestForSourceQuery.findUnitTests(root)) {
            FileObject fo = URLMapper.findFileObject(url);
            if (fo != null) {
                tests.add(fo);
            }
        }
        if (tests.isEmpty()) {
            Project owner = FileOwnerQuery.getOwner(root);
            for (FileObject other : roots) {
                if (!other.equals(root) && owner != null && owner.equals(FileOwnerQuery.getOwner(other))) {
                    tests.add(other);
                }
            }
        }
        return tests;
    }

    /** By real path: roots and files may be reached through symlinks, and differently (macOS /var vs. /private/var). */
    private static Path realPath(FileObject fo) {
        File f = fo == null ? null : FileUtil.toFile(fo);
        if (f == null) {
            return null;
        }
        try {
            return f.toPath().toRealPath();
        } catch (IOException ex) {
            return f.toPath().toAbsolutePath().normalize();
        }
    }

    static TestCommand command(Project p, File workspaceRoot, List<String> tests, FileObject testFile) {
        GradleBaseProject gradle = GradleBaseProject.get(p);
        if (gradle != null) {
            return TestCommand.gradle(gradle.getRootDir(), gradle.getProjectDir(), gradle.getPath(), tests);
        }
        File dir = FileUtil.toFile(p.getProjectDirectory());
        if (dir != null && new File(dir, "pom.xml").isFile()) {
            return TestCommand.maven(dir, workspaceRoot, tests);
        }
        if (dir != null && isNetBeansModule(dir)) {
            // test/<type>/src/...: the harness's test type
            String rel = FileUtil.getRelativePath(p.getProjectDirectory(), testFile);
            String[] parts = rel == null ? new String[0] : rel.split("/");
            String type = parts.length > 2 && parts[0].equals("test") && parts[2].equals("src") ? parts[1] : "unit";
            return TestCommand.netbeansModule(dir, type, tests);
        }
        return null;
    }

    /** A NetBeans module built by the NetBeans harness (Ant): nbproject/project.xml of the apisupport type. */
    private static boolean isNetBeansModule(File dir) {
        File projectXml = new File(dir, "nbproject/project.xml");
        if (!projectXml.isFile() || !new File(dir, "build.xml").isFile()) {
            return false;
        }
        try {
            return java.nio.file.Files.readString(projectXml.toPath()).contains("org.netbeans.modules.apisupport.project");
        } catch (IOException ex) {
            return false;
        }
    }

    /** Whether a class (binary name) is declared in the workspace sources; nested classes count as their top-level class. */
    private static Predicate<String> projectClass(List<FileObject> roots) {
        Map<String, Boolean> cache = new HashMap<>();
        return cls -> cache.computeIfAbsent(cls.contains("$") ? cls.substring(0, cls.indexOf('$')) : cls,
                c -> SymbolResolver.findSourceFile(c, roots) != null);
    }
}
