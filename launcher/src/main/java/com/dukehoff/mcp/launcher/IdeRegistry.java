/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.launcher;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The servers of running NetBeans IDEs with our plugin: each announces itself in
 * {@code ~/.dukehoff-mcp/ide/<pid>.json} ({@code DUKEHOFF_MCP_IDE_DIR} elsewhere) with its socket, and
 * removes the entry when it exits. {@code --ide} connects to the most recently started one that runs.
 *
 * Whether an IDE runs is decided by its socket accepting a connection, not by its process: an agent in a
 * sandbox with its own process namespace (bubblewrap's --unshare-pid, containers) does not see the IDE's
 * process. For the same reason this only reads the registry, which such a sandbox may mount read-only;
 * the IDE removes entries of ended IDEs when it starts.
 */
final class IdeRegistry {

    private static final Pattern SOCKET = Pattern.compile("\"socket\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private IdeRegistry() {
    }

    static Path dir(Map<String, String> env) {
        String dir = env.get("DUKEHOFF_MCP_IDE_DIR");
        return dir != null && !dir.isBlank() ? Paths.get(dir) : Paths.get(System.getProperty("user.home"), ".dukehoff-mcp", "ide");
    }

    /** The socket of the newest IDE that answers on it. */
    static Optional<Path> find(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(entries::add);
        }
        entries.sort(Comparator.comparing((Path p) -> {
            try {
                return Files.getLastModifiedTime(p);
            } catch (IOException ex) {
                return java.nio.file.attribute.FileTime.fromMillis(0);
            }
        }).reversed());
        for (Path entry : entries) {
            String text;
            try {
                text = Files.readString(entry, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                continue; // removed meanwhile, or not readable here
            }
            Matcher socket = SOCKET.matcher(text);
            if (socket.find()) {
                Path s = Paths.get(socket.group(1).replace("\\\\", "\\").replace("\\/", "/"));
                if (answers(s)) {
                    return Optional.of(s);
                }
            }
        }
        return Optional.empty();
    }

    /** Whether a server accepts connections on the socket (the IDE's server ignores a client that just closes). */
    static boolean answers(Path socket) {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            return true;
        } catch (IOException | UnsupportedOperationException ex) {
            return false;
        }
    }
}
