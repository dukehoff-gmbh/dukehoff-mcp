/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The build command that runs given tests of one project, through the project's own build so its
 * configuration (dependencies, argLine, plugins) applies, and where the build writes its JUnit XML
 * reports.
 *
 * @param kind "maven" or "gradle"
 * @param workDir where the command runs: the build's root (a multi-module Maven reactor, the Gradle
 *        root project), so sibling modules resolve without being installed
 * @param command the command line
 * @param reportDirs where the reports of this project land
 */
record TestCommand(String kind, File workDir, List<String> command, List<File> reportDirs) {

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /**
     * Maven: {@code mvn -B -Dtest=A,B#m -Dsurefire.failIfNoSpecifiedTests=false test}, from the
     * topmost directory with a pom.xml (within {@code limit}) and with {@code -pl <module> -am} when
     * that is not the project's own directory.
     *
     * @param tests classes ({@code com.acme.FooTest}) or methods ({@code com.acme.FooTest#bar})
     */
    static TestCommand maven(File projectDir, File limit, List<String> tests) {
        File root = projectDir;
        for (File d = projectDir.getParentFile(); d != null && isWithin(d, limit); d = d.getParentFile()) {
            if (new File(d, "pom.xml").isFile()) {
                root = d;
            }
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(executable(root, "mvnw", "mvn"));
        cmd.add("-B");
        if (!root.equals(projectDir)) {
            cmd.add("-pl");
            cmd.add(root.toPath().relativize(projectDir.toPath()).toString().replace(File.separatorChar, '/'));
            cmd.add("-am");
        }
        cmd.add("-Dtest=" + String.join(",", tests));
        // upstream modules built with -am have none of the tests: not an error there
        cmd.add("-Dsurefire.failIfNoSpecifiedTests=false");
        cmd.add("-DfailIfNoTests=false");
        cmd.add("test");
        return new TestCommand("maven", root, cmd, List.of(new File(projectDir, "target/surefire-reports")));
    }

    /**
     * Gradle: {@code gradlew :path:test --tests com.acme.FooTest --tests com.acme.FooTest.bar} from
     * the root project.
     *
     * @param projectPath the Gradle project path, ":" for the root project
     */
    static TestCommand gradle(File rootDir, File projectDir, String projectPath, List<String> tests) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable(rootDir, "gradlew", "gradle"));
        cmd.add(":".equals(projectPath) || projectPath.isEmpty() ? "test" : projectPath + ":test");
        for (String t : tests) {
            cmd.add("--tests");
            cmd.add(t.replace('#', '.'));
        }
        return new TestCommand("gradle", rootDir, cmd, List.of(new File(projectDir, "build/test-results")));
    }

    /**
     * A NetBeans module (Ant, the NetBeans harness): {@code ant test-single -Dtest.includes=a/b/FooTest.java}
     * for classes, {@code ant test-method -Dtest.class=a.b.FooTest -Dtest.methods=bar} for one method,
     * in the module's directory.
     *
     * @param testType the harness's test type: unit or qa-functional (the folder under test/)
     */
    static TestCommand netbeansModule(File moduleDir, String testType, List<String> tests) {
        List<String> cmd = new ArrayList<>();
        cmd.add(WINDOWS ? "ant.bat" : "ant");
        cmd.add("-f");
        cmd.add(new File(moduleDir, "build.xml").getAbsolutePath());
        cmd.add("-Dtest.type=" + testType);
        if (tests.size() == 1 && tests.get(0).contains("#")) {
            String t = tests.get(0);
            cmd.add("-Dtest.class=" + t.substring(0, t.indexOf('#')));
            cmd.add("-Dtest.methods=" + t.substring(t.indexOf('#') + 1));
            cmd.add("test-method");
        } else {
            // the harness selects test files; a method of several tests runs as its whole class
            cmd.add("-Dtest.includes=" + tests.stream()
                    .map(t -> (t.contains("#") ? t.substring(0, t.indexOf('#')) : t).replace('.', '/') + ".java")
                    .distinct().collect(java.util.stream.Collectors.joining(",")));
            cmd.add("test-single");
        }
        return new TestCommand("ant", moduleDir, cmd, List.of(new File(moduleDir, "build/test/" + testType + "/results")));
    }

    /** The build's wrapper in {@code root} if there is one, else the tool from the PATH. */
    private static String executable(File root, String wrapper, String tool) {
        File w = new File(root, WINDOWS ? wrapper + (wrapper.equals("mvnw") ? ".cmd" : ".bat") : wrapper);
        if (w.isFile()) {
            return w.getAbsolutePath();
        }
        return WINDOWS ? tool + (tool.equals("mvn") ? ".cmd" : ".bat") : tool;
    }

    private static boolean isWithin(File dir, File limit) {
        return dir.toPath().normalize().startsWith(limit.toPath().normalize());
    }

    /** Gradle's --debug-jvm always listens there. */
    static final int GRADLE_DEBUG_PORT = 5005;

    /**
     * The same command with the test JVM waiting for a debugger: Maven on {@code port} (surefire's
     * maven.surefire.debug, one fork), Gradle on {@link #GRADLE_DEBUG_PORT} (--debug-jvm).
     *
     * @return the command and the port the test JVM listens on
     */
    DebugCommand debugging(int port) {
        List<String> cmd = new ArrayList<>(command);
        if ("maven".equals(kind)) {
            cmd.add(cmd.size() - 1, "-Dmaven.surefire.debug=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port);
            cmd.add(cmd.size() - 1, "-DforkCount=1");
            cmd.add(cmd.size() - 1, "-DreuseForks=true"); // one JVM for all selected test classes: the debugger stays attached
            return new DebugCommand(new TestCommand(kind, workDir, cmd, reportDirs), port);
        }
        if ("ant".equals(kind)) {
            // the harness's hook for the IDE's JVM arguments: empty otherwise, so the module's
            // own test.run.args stay as they are
            cmd.add(cmd.size() - 1, "-Drun.jvmargs.ide=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port);
            return new DebugCommand(new TestCommand(kind, workDir, cmd, reportDirs), port);
        }
        cmd.add("--debug-jvm");
        return new DebugCommand(new TestCommand(kind, workDir, cmd, reportDirs), GRADLE_DEBUG_PORT);
    }

    /** A test command whose test JVM waits for a debugger on {@code port}. */
    record DebugCommand(TestCommand command, int port) {
    }

    /** The command as one line, for the result. */
    String display() {
        List<String> shown = new ArrayList<>(command);
        shown.set(0, new File(shown.get(0)).getName());
        return String.join(" ", shown);
    }
}
