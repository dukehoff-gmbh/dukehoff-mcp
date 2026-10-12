/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.util.List;
import java.util.Map;

/**
 * A tool offered to MCP clients.
 */
public interface Tool {

    /** Tool name as seen by the agent, e.g. {@code find_usages}. */
    String name();

    /**
     * Description shown to the agent. This is the agent's only documentation,
     * so it should say when to prefer the tool and what the input syntax is.
     */
    String description();

    /**
     * What agents type when they look for this tool, besides its name: "rename symbol", "who calls".
     * Clients that load tool definitions on demand (Copilot CLI, Claude Code) search the names and
     * descriptions for the literal text, so these are listed after the description.
     */
    default List<String> searchTerms() {
        return List.of();
    }

    /** JSON Schema of the {@code arguments} object. */
    Map<String, Object> inputSchema();

    /**
     * Executes the tool. Runs on a worker thread, never on the protocol reader thread.
     *
     * @param arguments the parsed {@code arguments} object, never {@code null}
     */
    Result call(Map<?, ?> arguments) throws Exception;

    /**
     * Text result of a tool call. Errors the agent can act on (unknown symbol,
     * workspace still indexing, ...) are returned as results with
     * {@code error == true}, not as protocol errors.
     */
    record Result(String text, boolean error) {

        public static Result ok(String text) {
            return new Result(text, false);
        }

        public static Result error(String text) {
            return new Result(text, true);
        }
    }
}
