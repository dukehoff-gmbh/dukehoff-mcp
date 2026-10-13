/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ProxyTest {

    private static List<String> fakeDaemon(String version) {
        return new ArrayList<>(List.of(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FakeDaemon.class.getName(), version));
    }

    @Test
    public void replacesADaemonOfAnotherVersionAndRelaysToTheNewOne() throws Exception {
        // a short base path: Unix domain socket paths are limited
        Path base = Files.createTempDirectory(Paths.get(System.getProperty("java.io.tmpdir")), "px").resolve("ws-1");
        Files.createDirectories(base);
        List<String> old = fakeDaemon("old");
        old.add("--mcp-socket");
        old.add(Proxy.socket(base).toString());
        Process oldDaemon = new ProcessBuilder(old).redirectErrorStream(true).start();
        Files.writeString(base.resolve(Launcher.PID_FILE), Long.toString(oldDaemon.pid()));
        long deadline = System.currentTimeMillis() + 20_000;
        Optional<Proxy.Connection> probe;
        while ((probe = Proxy.tryConnect(base)).isEmpty()) {
            assertTrue("the old daemon did not start", System.currentTimeMillis() < deadline);
            Thread.sleep(100);
        }
        probe.get().channel().close(); // the fake serves one connection at a time

        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        Proxy proxy = new Proxy(new PrintStream(messages, true, StandardCharsets.UTF_8));
        Proxy.Connection c = proxy.connect(base, "new", dir -> fakeDaemon("new"));
        try {
            assertEquals("new", c.hello().version());
            assertTrue("the old daemon must be gone", oldDaemon.waitFor(10, TimeUnit.SECONDS));
            assertTrue(messages.toString(StandardCharsets.UTF_8), messages.toString(StandardCharsets.UTF_8).contains("restarting it"));
            assertEquals(Long.toString(c.hello().pid()), Files.readString(base.resolve(Launcher.PID_FILE)));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int status = proxy.relay(c, "core", null, new ByteArrayInputStream("{\"a\":1}\n{\"b\":2}\n".getBytes(StandardCharsets.UTF_8)), out);
            assertEquals("echo:{\"a\":1}\necho:{\"b\":2}\n", out.toString(StandardCharsets.UTF_8));
            assertEquals("the client closed stdin", 0, status);

            // an unknown tool selection: the daemon's message goes to stderr, nothing to the client
            Proxy.Connection refused = proxy.connect(base, "new", dir -> {
                throw new AssertionError("must not start another daemon");
            });
            ByteArrayOutputStream nothing = new ByteArrayOutputStream();
            assertEquals(2, proxy.relay(refused, "bad", null, new ByteArrayInputStream(new byte[0]), nothing));
            assertEquals("", nothing.toString(StandardCharsets.UTF_8));
            assertTrue(messages.toString(StandardCharsets.UTF_8),
                    messages.toString(StandardCharsets.UTF_8).contains("dukehoff-mcp: Unknown tool, group or preset 'bad' in --tools bad. Presets: copilot/x"));

            // the same version is reused, not restarted
            Proxy.Connection again = proxy.connect(base, "new", dir -> {
                throw new AssertionError("must not start another daemon");
            });
            assertEquals(c.hello().pid(), again.hello().pid());
            proxy.stop(again);
            assertFalse(ProcessHandle.of(c.hello().pid()).map(ProcessHandle::isAlive).orElse(false));
            assertEquals(Optional.empty(), Proxy.tryConnect(base));
        } finally {
            ProcessHandle.of(c.hello().pid()).ifPresent(ProcessHandle::destroyForcibly);
            oldDaemon.destroyForcibly();
        }
    }

    @Test
    public void parsesTheHello() {
        Proxy.Hello hello = Proxy.parseHello("{\"pid\":4711,\"dukehoffmcp\":\"daemon\",\"version\":\"0.1.0-20261006 202610061321\"}");
        assertNotNull(hello);
        assertEquals("0.1.0-20261006 202610061321", hello.version());
        assertEquals(4711, hello.pid());
        assertEquals(null, Proxy.parseHello("{\"jsonrpc\":\"2.0\"}"));
    }

    @Test
    public void longSocketPathsMoveToATempDirectory() {
        Path deep = Paths.get("/data", "x".repeat(120), "ws-1");
        Path socket = Proxy.socket(deep);
        assertTrue(socket.toString(), socket.toString().length() <= 100 || !socket.startsWith(deep));
        assertEquals(Paths.get("/data/ws-1").resolve(Proxy.SOCKET), Proxy.socket(Paths.get("/data/ws-1")));
    }
}
