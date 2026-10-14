/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public class ToolSelectionTest {

    static final List<String> ALL = List.of("find_symbol", "find_usages", "find_implementations", "outline", "diagnostics",
            "rename", "move", "change_signature", "safe_delete", "inline", "apply_rule", "debug", "workspace_status");

    static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public Result call(Map<?, ?> arguments) {
                return Result.ok("");
            }
        };
    }

    private static List<String> select(String spec) {
        return ToolSelection.select(ALL.stream().map(ToolSelectionTest::tool).toList(), spec).stream()
                .map(Tool::name).toList();
    }

    @Test
    public void withoutSelectionEveryToolIsOffered() {
        assertEquals(ALL, select(null));
        assertEquals(ALL, select(" "));
        assertEquals(ALL, select("all"));
        assertEquals(ALL, select("claude-code"));
    }

    @Test
    public void groupsPresetsAndToolsCombineInTheCanonicalOrder() {
        assertEquals(List.of("find_usages", "outline", "diagnostics", "rename"), select("core"));
        assertEquals(select("core"), select("copilot"));
        assertEquals(List.of("find_usages", "outline", "diagnostics", "rename", "move", "change_signature", "safe_delete",
                "inline"), select("core,refactor"));
        assertEquals(List.of("find_usages", "outline", "diagnostics", "rename", "apply_rule"), select("copilot, migrate"));
        assertEquals(List.of("find_symbol", "workspace_status"), select("workspace_status,find_symbol"));
    }

    @Test
    public void unknownNamesAreRejectedWithWhatExists() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> select("core,refactoring"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("'refactoring'"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("Presets: copilot, claude-code"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("refactor (change_signature, move"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("tools: find_symbol, find_usages"));
    }

    @Test
    public void instructionsForAllToolsAreUnchanged() {
        // the text agents saw before groups existed; evaluations compare against it
        String before = "Semantic Java code intelligence backed by the NetBeans Java infrastructure "
                + "(the same parser, index and refactoring engine the IDE uses). "
                + "Prefer these tools over grep/text search for Java questions: results are exact "
                + "(overloads, overrides, imports and same-named symbols are told apart) and compact. "
                + "Symbols are written as fully qualified names: com.acme.Foo, com.acme.Foo.Inner, "
                + "com.acme.Foo#bar, com.acme.Foo#bar(String,int), com.acme.Foo#<init>(); the package may be "
                + "left out when the type name is unique in the workspace (Foo#bar). "
                + "Which tool: who calls a method, or where a type, field or constructor is used -> "
                + "find_usages (grep cannot tell which class a call belongs to and misses static "
                + "imports); subclasses and implementations -> find_implementations; the exact API of a class "
                + "of the JDK or a dependency (its methods, overloads, deprecations) before calling it -> outline or "
                + "find_symbol with include_libraries (instead of guessing; grep cannot look into jars); renaming, moving, "
                + "changing a signature -> the refactoring tools (one call updates every reference). "
                + "Use find_symbol when you only know a simple or partial type name. "
                + "Clients show these tools with the server name in front (java-find_usages, "
                + "mcp__java__find_usages); to look one up, search for its own name, e.g. find_usages. "
                + "Files edited on disk are picked up automatically before each call.";
        // the twelve tools of then: the same text; debug adds its clause
        Set<String> twelve = new LinkedHashSet<>(ALL);
        twelve.remove("debug");
        assertEquals(before, McpServer.instructions(twelve));
        String all = McpServer.instructions(new LinkedHashSet<>(ALL));
        assertTrue(all, all.contains("; why a value is wrong at runtime, or where an exception comes from -> debug"));
    }

    @Test
    public void instructionsNameOnlyOfferedTools() {
        Set<String> core = new LinkedHashSet<>(select("core"));
        String text = McpServer.instructions(core);
        assertTrue(text, text.contains("-> find_usages"));
        assertTrue(text, text.contains("renaming -> the refactoring tools"));
        assertTrue(text, text.contains("before calling it -> outline (instead of guessing"));
        assertFalse(text, text.contains("find_implementations"));
        assertFalse(text, text.contains("find_symbol"));

        String migrate = McpServer.instructions(Set.of("apply_rule"));
        assertFalse(migrate, migrate.contains("Which tool"));
        assertTrue(migrate, migrate.contains("(java-apply_rule, mcp__java__apply_rule)"));
        assertFalse(migrate, migrate.contains("find_usages"));
    }

    @Test
    public void toolListFollowsTheSelection() throws Exception {
        String in = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}\n";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        List<Tool> selected = ToolSelection.select(ALL.stream().map(ToolSelectionTest::tool).toList(), "copilot");
        new McpServer(new java.io.ByteArrayInputStream(in.getBytes(java.nio.charset.StandardCharsets.UTF_8)), out, selected).run();
        String response = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        Set<String> listed = java.util.regex.Pattern.compile("\"name\":\"([a-z_]+)\"").matcher(response).results()
                .map(m -> m.group(1)).collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(new LinkedHashSet<>(select("copilot")), listed);
    }
}
