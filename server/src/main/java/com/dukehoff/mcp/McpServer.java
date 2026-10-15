/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.openide.util.RequestProcessor;

/**
 * Model Context Protocol server over the stdio transport: newline-delimited
 * JSON-RPC 2.0 messages on the given streams.
 * <p>
 * Deliberately small and dependency-free (it uses json_simple, which the IDE
 * already ships). Only the {@code tools} capability is implemented. If the
 * protocol surface grows, this class is the one place to replace with the
 * official MCP Java SDK.
 */
public final class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());
    private static final int SLOW_CALL_MILLIS = 30_000;
    private static final int SLOW_CALL_REPEAT_MILLIS = 60_000;
    private static final RequestProcessor WATCHDOG = new RequestProcessor("MCP slow call watchdog", 1, true);
    private static volatile boolean allThreadsLogged;

    static final String LATEST_PROTOCOL_VERSION = "2025-11-25";
    static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
            "2024-11-05", "2025-03-26", "2025-06-18", LATEST_PROTOCOL_VERSION);

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    /**
     * The server instructions for a tool set: they map questions to tools, so they name only offered
     * ones. Claude Code shows only tool names and these instructions until a tool is loaded, so they
     * decide whether an agent reaches for a tool at all.
     */
    static String instructions(Set<String> offered) {
        StringBuilder sb = new StringBuilder("Semantic Java code intelligence backed by the NetBeans Java infrastructure "
                + "(the same parser, index and refactoring engine the IDE uses). "
                + "Prefer these tools over grep/text search for Java questions: results are exact "
                + "(overloads, overrides, imports and same-named symbols are told apart) and compact. "
                + "Symbols are written as fully qualified names: com.acme.Foo, com.acme.Foo.Inner, "
                + "com.acme.Foo#bar, com.acme.Foo#bar(String,int), com.acme.Foo#<init>(); the package may be "
                + "left out when the type name is unique in the workspace (Foo#bar). ");
        List<String> which = new ArrayList<>();
        if (offered.contains("find_usages")) {
            which.add("who calls a method, or where a type, field or constructor is used -> "
                    + "find_usages (grep cannot tell which class a call belongs to and misses static imports)");
        }
        if (offered.contains("find_implementations")) {
            which.add("subclasses and implementations -> find_implementations");
        }
        if (offered.contains("outline")) {
            which.add("the exact API of a class of the JDK or a dependency (its methods, overloads, deprecations) before "
                    + "calling it -> outline" + (offered.contains("find_symbol") ? " or find_symbol with include_libraries" : "")
                    + " (instead of guessing; grep cannot look into jars)");
        }
        List<String> refactorings = new ArrayList<>();
        for (String[] r : new String[][]{{"rename", "renaming"}, {"move", "moving"}, {"change_signature", "changing a signature"},
            {"safe_delete", "deleting"}, {"inline", "inlining"}}) {
            if (offered.contains(r[0])) {
                refactorings.add(r[1]);
            }
        }
        if (!refactorings.isEmpty()) {
            // with all of them offered, the text names the three agents ask for most, as before groups existed
            String what = refactorings.size() == 5 ? "renaming, moving, changing a signature" : String.join(", ", refactorings);
            which.add(what + " -> the refactoring tools (one call updates every reference)");
        }
        if (offered.contains("debug")) {
            which.add("why a value is wrong at runtime, or where an exception comes from -> debug (stops a test "
                    + "once and shows the stack and variables, instead of adding println and rerunning)");
        }
        if (!which.isEmpty()) {
            sb.append("Which tool: ").append(String.join("; ", which)).append(". ");
        }
        if (offered.contains("find_symbol")) {
            sb.append("Use find_symbol when you only know a simple or partial type name. ");
        }
        String example = offered.contains("find_usages") ? "find_usages" : offered.stream().findFirst().orElse("find_usages");
        return sb.append("Clients show these tools with the server name in front (java-").append(example)
                .append(", mcp__java__").append(example).append("); to look one up, search for its own name, e.g. ")
                .append(example).append(". Files edited on disk are picked up automatically before each call.").toString();
    }

    private final BufferedReader in;
    private final Writer out;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final RequestProcessor worker = new RequestProcessor("MCP tool calls", 4, true);
    /** Folders this client's registration lets tools write to besides the workspace ({@link WriteBoundary}). */
    private final List<String> allowWrites;

    public McpServer(InputStream in, OutputStream out, List<? extends Tool> tools) {
        this(in, out, tools, List.of());
    }

    public McpServer(InputStream in, OutputStream out, List<? extends Tool> tools, List<String> allowWrites) {
        this.allowWrites = List.copyOf(allowWrites);
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        for (Tool t : tools) {
            this.tools.put(t.name(), t);
        }
    }

    /**
     * Reads and dispatches messages until the input stream is closed.
     */
    public void run() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Object message;
            try {
                message = new JSONParser().parse(line);
            } catch (ParseException ex) {
                sendError(null, PARSE_ERROR, "Parse error: " + ex);
                continue;
            }
            if (message instanceof List<?> batch) {
                for (Object m : batch) {
                    dispatch(m);
                }
            } else {
                dispatch(message);
            }
        }
    }

    private void dispatch(Object message) {
        if (!(message instanceof Map<?, ?> m)) {
            sendError(null, INVALID_REQUEST, "Invalid request");
            return;
        }
        if (!(m.get("method") instanceof String method)) {
            // a response to a server-initiated request; we never send any
            return;
        }
        boolean isNotification = !m.containsKey("id");
        Object id = m.get("id");
        Map<?, ?> params = m.get("params") instanceof Map<?, ?> p ? p : Map.of();
        switch (method) {
            case "initialize" -> {
                // for eval/metrics.py: how long the JVM took to start up to here
                LOG.log(Level.INFO, "dukehoff-mcp timing: initialize {0} {1}", new Object[]{
                    Long.toString(ManagementFactory.getRuntimeMXBean().getStartTime()),
                    Long.toString(System.currentTimeMillis())});
                sendResult(id, initialize(params));
            }
            case "ping" -> sendResult(id, Map.of());
            case "tools/list" -> sendResult(id, listTools());
            case "tools/call" -> worker.post(() -> callTool(id, params));
            default -> {
                // notifications/initialized, notifications/cancelled, ... need no answer
                if (!isNotification) {
                    sendError(id, METHOD_NOT_FOUND, "Method not found: " + method);
                }
            }
        }
    }

    private Map<String, Object> initialize(Map<?, ?> params) {
        Object requested = params.get("protocolVersion");
        String version = requested instanceof String v && SUPPORTED_PROTOCOL_VERSIONS.contains(v)
                ? v : LATEST_PROTOCOL_VERSION;
        return Json.obj(
                "protocolVersion", version,
                "capabilities", Json.obj("tools", Json.obj("listChanged", false)),
                "serverInfo", Json.obj("name", "dukehoff-mcp", "version", "0.1"),
                "instructions", instructions(tools.keySet()));
    }

    private Map<String, Object> listTools() {
        List<Object> list = new ArrayList<>();
        Offered.set(tools.keySet());
        try {
            for (Tool t : tools.values()) {
                list.add(Json.obj(
                        "name", t.name(),
                        "description", t.description() + searchLine(t),
                        "inputSchema", t.inputSchema()));
            }
        } finally {
            Offered.clear();
        }
        return Json.obj("tools", list);
    }

    /**
     * Agents search for tools with text such as "java rename" or "rename symbol", while clients
     * show the name as java-rename or mcp__java__rename: put both kinds of text in the
     * description. "java" is the server name every configuration printed by the launcher uses.
     */
    static String searchLine(Tool t) {
        StringBuilder sb = new StringBuilder("\nSearch terms: java ").append(t.name());
        for (String term : t.searchTerms()) {
            sb.append(", ").append(term);
        }
        return sb.append('.').toString();
    }

    private void callTool(Object id, Map<?, ?> params) {
        Object name = params.get("name");
        Tool tool = name instanceof String n ? tools.get(n) : null;
        if (tool == null) {
            sendError(id, INVALID_PARAMS, "Unknown tool: " + name);
            return;
        }
        Map<?, ?> args = params.get("arguments") instanceof Map<?, ?> a ? a : Map.of();
        long start = System.currentTimeMillis();
        SlowCall slow = new SlowCall(tool.name(), args, Thread.currentThread(), start, SLOW_CALL_MILLIS);
        Tool.Result result;
        Offered.set(tools.keySet());
        WriteBoundary.set(allowWrites);
        try {
            result = tool.call(args);
        } catch (Exception | LinkageError | AssertionError ex) {
            LOG.log(Level.INFO, "Tool " + name + " failed", ex);
            result = Tool.Result.error(tool.name() + " failed: " + ex);
        } finally {
            Offered.clear();
            WriteBoundary.clear();
            slow.stop();
        }
        // clients give up after their own timeout (Copilot: 60 s) and say only "timed out"; the log says where
        LOG.log(Level.INFO, "dukehoff-mcp call: {0} {1} ms{2}", new Object[]{tool.name(),
            Long.toString(System.currentTimeMillis() - start), result.error() ? " (error)" : ""});
        sendResult(id, Json.obj(
                "content", List.of(Json.obj("type", "text", "text", result.text())),
                "isError", result.error()));
    }

    /**
     * Logs the stack of a tool call that is still running after a delay ({@value #SLOW_CALL_MILLIS} ms), then every
     * {@value #SLOW_CALL_REPEAT_MILLIS} ms; the first slow call of a session also logs all threads, which
     * shows what it waits for (a scan, a lock, a project load) without a debugger at the user's site.
     */
    static final class SlowCall implements Runnable {

        private final String tool;
        private final Map<?, ?> args;
        private final Thread thread;
        private final long start;
        private final RequestProcessor.Task task;
        // the worker thread outlives the call and runs the next one
        private volatile boolean stopped;

        SlowCall(String tool, Map<?, ?> args, Thread thread, long start, int delayMillis) {
            this.tool = tool;
            this.args = args;
            this.thread = thread;
            this.start = start;
            this.task = WATCHDOG.create(this);
            task.schedule(delayMillis);
        }

        void stop() {
            stopped = true;
            task.cancel();
        }

        @Override
        public void run() {
            if (stopped) {
                return;
            }
            StringBuilder sb = new StringBuilder("dukehoff-mcp slow call: ").append(tool).append(' ')
                    .append(JSONValue.toJSONString(args)).append(" still running after ")
                    .append((System.currentTimeMillis() - start) / 1000).append(" s\n");
            appendStack(sb, thread, thread.getStackTrace());
            if (!allThreadsLogged) {
                allThreadsLogged = true;
                sb.append("All threads:\n");
                Thread.getAllStackTraces().forEach((t, stack) -> {
                    if (t != thread) {
                        appendStack(sb, t, stack);
                    }
                });
            }
            LOG.warning(sb.toString());
            if (!stopped) {
                task.schedule(SLOW_CALL_REPEAT_MILLIS);
            }
        }

        private static void appendStack(StringBuilder sb, Thread t, StackTraceElement[] stack) {
            sb.append('"').append(t.getName()).append("\" ").append(t.getState()).append('\n');
            for (StackTraceElement e : stack) {
                sb.append("\tat ").append(e).append('\n');
            }
        }
    }

    private void sendResult(Object id, Object result) {
        send(Json.obj("jsonrpc", "2.0", "id", id, "result", result));
    }

    private void sendError(Object id, int code, String message) {
        send(Json.obj("jsonrpc", "2.0", "id", id, "error", Json.obj("code", code, "message", message)));
    }

    private synchronized void send(Map<String, Object> message) {
        try {
            // JSONValue escapes line breaks inside strings, so each message stays on one line
            out.write(JSONValue.toJSONString(message));
            out.write('\n');
            out.flush();
        } catch (IOException ex) {
            LOG.log(Level.INFO, "Cannot write MCP message", ex);
        }
    }
}
