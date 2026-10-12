/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.util.Set;

/**
 * The tools offered to the client whose request the current thread handles ({@link ToolSelection}),
 * so descriptions and hints name only tools the agent has. Tool instances are shared by all
 * connections of the persistent server, so this is per call, not per tool.
 */
public final class Offered {

    private static final ThreadLocal<Set<String>> CURRENT = new ThreadLocal<>();

    private Offered() {
    }

    /** Whether the current client has the tool; true outside a client request (all tools). */
    public static boolean has(String tool) {
        Set<String> offered = CURRENT.get();
        return offered == null || offered.contains(tool);
    }

    static void set(Set<String> tools) {
        CURRENT.set(tools);
    }

    static void clear() {
        CURRENT.remove();
    }
}
