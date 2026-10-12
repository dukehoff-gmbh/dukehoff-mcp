/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONValue;

/**
 * The persistent server: listens on a Unix domain socket and runs one MCP session per connection,
 * all on one workspace and its index, so a new session answers at once. The launcher's proxy
 * relays a client's stdio to it.
 * <p>
 * Per connection the daemon first writes {@code {"dukehoffmcp":"daemon","version":...,"pid":...}}; the
 * proxy answers {@code {"dukehoffmcp":"connect"}} (then MCP lines follow) or {@code {"dukehoffmcp":"stop"}}
 * (e.g. after a version mismatch). The daemon exits when no client was connected for the idle
 * time, or when the workspace folder is gone.
 */
final class Daemon {

    private static final Logger LOG = Logger.getLogger(Daemon.class.getName());
    /** Without a client for this long, the daemon exits; {@code -J-Ddukehoffmcp.idle.minutes=<n>}. */
    static final int IDLE_MINUTES = Integer.getInteger("dukehoffmcp.idle.minutes", 30);
    private static final long CHECK_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private final Path socket;
    private final File workspaceRoot;
    private final List<Tool> tools;
    private final String version;
    private final AtomicInteger clients = new AtomicInteger();
    private volatile long idleSince = System.currentTimeMillis();
    private volatile ServerSocketChannel server;

    Daemon(Path socket, File workspaceRoot, List<Tool> tools, String version) {
        this.socket = socket;
        this.workspaceRoot = workspaceRoot;
        this.tools = tools;
        this.version = version;
    }

    /** Serves clients until stopped, idle for too long, or the workspace is gone. */
    void run() throws IOException {
        Files.deleteIfExists(socket); // a dead daemon's; the proxy only starts us when nothing answered
        try (ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.bind(UnixDomainSocketAddress.of(socket));
            try {
                Files.setPosixFilePermissions(socket, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ex) {
                // Windows: the socket inherits the ACL of the user's data directory
            }
            server = channel;
            Thread watchdog = new Thread(this::watch, "dukehoff-mcp daemon watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            LOG.log(Level.INFO, "dukehoff-mcp daemon {0} listening on {1}, idle exit after {2} min",
                    new Object[]{version, socket, IDLE_MINUTES});
            while (true) {
                SocketChannel client;
                try {
                    client = channel.accept();
                } catch (ClosedChannelException ex) {
                    return; // stop() closed it
                }
                Thread t = new Thread(() -> serve(client), "dukehoff-mcp client");
                t.setDaemon(true);
                t.start();
            }
        } finally {
            Files.deleteIfExists(socket);
        }
    }

    void stop(String why) {
        LOG.log(Level.INFO, "dukehoff-mcp daemon stops: {0}", why);
        try {
            ServerSocketChannel s = server;
            if (s != null) {
                s.close();
            }
        } catch (IOException ex) {
            LOG.log(Level.INFO, "Closing the daemon socket failed", ex);
        }
    }

    private void watch() {
        while (server != null && server.isOpen()) {
            try {
                Thread.sleep(CHECK_MILLIS);
            } catch (InterruptedException ex) {
                return;
            }
            if (!workspaceRoot.isDirectory()) {
                stop("the workspace " + workspaceRoot + " is gone");
            } else if (clients.get() == 0
                    && System.currentTimeMillis() - idleSince > TimeUnit.MINUTES.toMillis(IDLE_MINUTES)) {
                stop("no client for " + IDLE_MINUTES + " minutes");
            }
        }
    }

    private void serve(SocketChannel channel) {
        try (channel) {
            InputStream in = input(channel);
            OutputStream out = output(channel);
            out.write((JSONValue.toJSONString(Map.of("dukehoffmcp", "daemon", "version", version,
                    "pid", ProcessHandle.current().pid())) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String request = readLine(in);
            if (request == null) {
                return;
            }
            Map<?, ?> message = JSONValue.parse(request) instanceof Map<?, ?> m ? m : Map.of();
            Object command = message.get("dukehoffmcp");
            if ("stop".equals(command)) {
                stop("asked by a client (" + request.strip() + ")");
                return;
            }
            if (!"connect".equals(command)) {
                LOG.log(Level.INFO, "dukehoff-mcp daemon: unexpected first line {0}", request.strip());
                return;
            }
            // each registration (and so each connection) may offer other tools; one index serves all
            List<Tool> selected;
            try {
                selected = ToolSelection.select(tools, message.get("tools") instanceof String s ? s : null);
            } catch (IllegalArgumentException ex) {
                out.write((JSONValue.toJSONString(Map.of("dukehoffmcp", "error", "message", ex.getMessage())) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            out.write((JSONValue.toJSONString(Map.of("dukehoffmcp", "connected", "tools",
                    selected.stream().map(Tool::name).toList())) + "\n").getBytes(StandardCharsets.UTF_8));
            int now = clients.incrementAndGet();
            LOG.log(Level.INFO, "dukehoff-mcp daemon: client connected with {0} tools, {1} connected",
                    new Object[]{selected.size(), now});
            try {
                new McpServer(in, out, selected).run();
            } finally {
                idleSince = System.currentTimeMillis();
                LOG.log(Level.INFO, "dukehoff-mcp daemon: client disconnected, {0} connected", clients.decrementAndGet());
            }
        } catch (IOException ex) {
            LOG.log(Level.INFO, "dukehoff-mcp daemon: connection failed", ex);
        }
    }

    /** One line without its line end, read byte by byte so nothing after it is consumed; null at EOF. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            line.write(b);
        }
        return b == -1 && line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
    }

    // Channels.newInputStream/newOutputStream may serialize reads and writes on one channel: a pending
    // read would block the answers. SocketChannel reads and writes are independent, so use them directly.

    private static InputStream input(SocketChannel channel) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                int n = channel.read(ByteBuffer.wrap(b, off, len));
                return n < 0 ? -1 : n;
            }
        };
    }

    private static OutputStream output(SocketChannel channel) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        };
    }
}
