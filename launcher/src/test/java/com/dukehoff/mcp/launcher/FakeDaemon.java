/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.launcher;

import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Speaks the daemon side of the proxy protocol for {@link ProxyTest}: hello with the version given as
 * first argument, then "stop" exits, "connect" with the tools "bad" is refused, and any other
 * "connect" is confirmed and echoes every line back with "echo:" in front.
 * Usage: {@code FakeDaemon <version> ... --mcp-socket <path>}, like the daemon's command line.
 */
public final class FakeDaemon {

    public static void main(String[] args) throws Exception {
        String version = args[0];
        List<String> list = List.of(args);
        Path socket = Paths.get(list.get(list.indexOf("--mcp-socket") + 1));
        Files.deleteIfExists(socket);
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            while (true) {
                try (SocketChannel client = server.accept()) {
                    write(client, "{\"dukehoffmcp\":\"daemon\",\"version\":\"" + version + "\",\"pid\":" + ProcessHandle.current().pid() + "}\n");
                    String first = readLine(client);
                    if (first == null) {
                        continue;
                    }
                    if (first.contains("\"stop\"")) {
                        Files.deleteIfExists(socket);
                        System.exit(0);
                    }
                    if (first.contains("\"tools\":\"bad\"")) {
                        write(client, "{\"dukehoffmcp\":\"error\",\"message\":\"Unknown tool, group or preset 'bad' in --tools bad. Presets: copilot\\/x\"}\n");
                        continue;
                    }
                    write(client, "{\"dukehoffmcp\":\"connected\",\"tools\":[]}\n");
                    String line;
                    while ((line = readLine(client)) != null) {
                        write(client, "echo:" + line + "\n");
                    }
                }
            }
        }
    }

    private static void write(SocketChannel channel, String text) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (b.hasRemaining()) {
            channel.write(b);
        }
    }

    private static String readLine(SocketChannel channel) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        ByteBuffer one = ByteBuffer.allocate(1);
        while (true) {
            one.clear();
            if (channel.read(one) == -1) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
            }
            if (one.get(0) == '\n') {
                return line.toString(StandardCharsets.UTF_8);
            }
            line.write(one.get(0));
        }
    }
}
