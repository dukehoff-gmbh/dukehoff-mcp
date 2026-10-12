/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which tools a client registration offers: {@code --tools} takes tool names, groups and presets,
 * comma separated. Groups follow workflows and may overlap (a refactoring needs find_usages before
 * and diagnostics after); presets fit a client's tool limits and context size. Without a selection
 * every tool is offered.
 */
public final class ToolSelection {

    /** Draft groups (2026-10-06); the final split follows the baseline measurement. */
    static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();
    static final Map<String, List<String>> PRESETS = new LinkedHashMap<>();
    static final String ALL = "all";

    static {
        GROUPS.put("core", List.of("diagnostics", "find_usages", "outline", "rename"));
        GROUPS.put("refactor", List.of("change_signature", "move", "safe_delete", "inline", "find_usages", "diagnostics"));
        GROUPS.put("migrate", List.of("apply_rule"));
        GROUPS.put("debug", List.of("debug"));
        PRESETS.put("copilot", List.of("core"));
        PRESETS.put("claude-code", List.of(ALL));
    }

    private ToolSelection() {
    }

    /**
     * The tools a selection offers, in the order of {@code all}.
     *
     * @param spec comma separated tool names, groups and presets; null or blank for all tools
     * @throws IllegalArgumentException for an unknown name; the message lists the known ones
     */
    public static List<Tool> select(List<Tool> all, String spec) {
        if (spec == null || spec.isBlank()) {
            return all;
        }
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool t : all) {
            byName.put(t.name(), t);
        }
        Set<String> names = new LinkedHashSet<>();
        for (String token : spec.split(",")) {
            add(token.strip(), byName, names, spec);
        }
        List<Tool> selected = new ArrayList<>();
        for (Tool t : all) {
            if (names.contains(t.name())) {
                selected.add(t);
            }
        }
        return selected;
    }

    private static void add(String token, Map<String, Tool> byName, Set<String> into, String spec) {
        if (token.isEmpty()) {
            return;
        }
        if (token.equals(ALL)) {
            into.addAll(byName.keySet());
        } else if (PRESETS.containsKey(token)) {
            PRESETS.get(token).forEach(t -> add(t, byName, into, spec));
        } else if (GROUPS.containsKey(token)) {
            GROUPS.get(token).forEach(t -> add(t, byName, into, spec));
        } else if (byName.containsKey(token)) {
            into.add(token);
        } else {
            throw new IllegalArgumentException("Unknown tool, group or preset '" + token + "' in --tools " + spec
                    + ". Presets: " + String.join(", ", PRESETS.keySet())
                    + "; groups: " + GROUPS.entrySet().stream().map(e -> e.getKey() + " (" + String.join(", ", e.getValue()) + ")")
                            .collect(Collectors.joining(", "))
                    + ", " + ALL + "; tools: " + String.join(", ", byName.keySet()) + ".");
        }
    }
}
