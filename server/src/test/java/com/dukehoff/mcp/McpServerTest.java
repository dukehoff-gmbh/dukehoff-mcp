/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Test;

public class McpServerTest {

    private static Tool tool(String name, List<String> terms) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Does things.";
            }

            @Override
            public List<String> searchTerms() {
                return terms;
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

    @Test
    public void toolListPutsSearchTermsIntoTheDescription() throws Exception {
        // what Copilot CLI's tool search failed on in practice: "netbeans rename", "rename symbol"
        String in = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new McpServer(new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)), out,
                List.of(tool("rename", List.of("rename symbol", "rename method")), tool("plain", List.of()))).run();
        String response = out.toString(StandardCharsets.UTF_8);
        assertTrue(response, response.contains("Does things.\\nSearch terms: java rename, rename symbol, rename method."));
        assertTrue(response, response.contains("Does things.\\nSearch terms: java plain."));
    }

    @Test
    public void slowCallLogsWhereItWaits() throws Exception {
        // a client may report only "Request timed out"; the log must say where the call hangs
        List<String> logged = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger log = Logger.getLogger(McpServer.class.getName());
        log.addHandler(handler);
        log.setUseParentHandlers(false); // keep the thread dump out of the build output
        CountDownLatch release = new CountDownLatch(1);
        Thread call = new Thread(() -> waitingForTheScan(release), "MCP tool calls test");
        call.start();
        McpServer.SlowCall slow = new McpServer.SlowCall("diagnostics", Map.of("files", List.of("A.java")),
                call, System.currentTimeMillis(), 100);
        try {
            long deadline = System.currentTimeMillis() + 5000;
            while (logged.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(logged.toString(), 1, logged.size());
            String first = logged.get(0);
            assertTrue(first, first.startsWith("dukehoff-mcp slow call: diagnostics {\"files\":[\"A.java\"]} still running after 0 s"));
            assertTrue(first, first.contains("\"MCP tool calls test\" WAITING"));
            assertTrue(first, first.contains("McpServerTest.waitingForTheScan"));
            assertTrue(first, first.contains("All threads:"));
        } finally {
            slow.stop();
            release.countDown();
            call.join();
            log.removeHandler(handler);
            log.setUseParentHandlers(true);
        }
    }

    private static void waitingForTheScan(CountDownLatch release) {
        try {
            release.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
