/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.File;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TestCommandTest {

    private final File module = new File("/src/netbeans/java/java.hints");

    private static String args(TestCommand c) {
        return String.join(" ", c.command().subList(1, c.command().size()));
    }

    @Test
    public void netbeansModuleOneMethod() {
        TestCommand c = TestCommand.netbeansModule(module, "unit", List.of("org.acme.FooTest#testBar"));
        assertEquals("-f " + new File(module, "build.xml").getAbsolutePath()
                + " -Dtest.type=unit -Dtest.class=org.acme.FooTest -Dtest.methods=testBar test-method", args(c));
        assertEquals(List.of(new File(module, "build/test/unit/results")), c.reportDirs());
    }

    @Test
    public void netbeansModuleClasses() {
        TestCommand c = TestCommand.netbeansModule(module, "qa-functional",
                List.of("org.acme.FooTest", "org.acme.BarTest#testX", "org.acme.FooTest#testY"));
        assertEquals("-f " + new File(module, "build.xml").getAbsolutePath()
                + " -Dtest.type=qa-functional -Dtest.includes=org/acme/FooTest.java,org/acme/BarTest.java test-single", args(c));
    }

    @Test
    public void netbeansModuleDebugging() {
        TestCommand.DebugCommand d = TestCommand.netbeansModule(module, "unit", List.of("org.acme.FooTest")).debugging(4711);
        List<String> cmd = d.command().command();
        assertEquals("test-single", cmd.get(cmd.size() - 1));
        assertEquals("-Drun.jvmargs.ide=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:4711",
                cmd.get(cmd.size() - 2));
        assertEquals(4711, d.port());
    }

    @Test
    public void mavenDebugging() {
        TestCommand.DebugCommand d = TestCommand.maven(module, module, List.of("org.acme.FooTest")).debugging(4711);
        List<String> cmd = d.command().command();
        assertEquals("test", cmd.get(cmd.size() - 1));
        assertEquals("-DreuseForks=true", cmd.get(cmd.size() - 2));
    }
}
