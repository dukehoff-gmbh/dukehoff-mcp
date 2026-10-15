/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.launcher;

import java.io.IOException;
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
 */
final class IdeRegistry {

    private static final Pattern SOCKET = Pattern.compile("\"socket\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");

    private IdeRegistry() {
    }

    static Path dir(Map<String, String> env) {
        String dir = env.get("DUKEHOFF_MCP_IDE_DIR");
        return dir != null && !dir.isBlank() ? Paths.get(dir) : Paths.get(System.getProperty("user.home"), ".dukehoff-mcp", "ide");
    }

    /** The socket of the newest running IDE; entries of ended processes are removed. */
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
        Optional<Path> found = Optional.empty();
        for (Path entry : entries) {
            String text = Files.readString(entry, StandardCharsets.UTF_8);
            Matcher socket = SOCKET.matcher(text);
            Matcher pid = PID.matcher(text);
            boolean alive = pid.find() && ProcessHandle.of(Long.parseLong(pid.group(1))).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                Files.deleteIfExists(entry);
            } else if (found.isEmpty() && socket.find()) {
                Path s = Paths.get(socket.group(1).replace("\\\\", "\\").replace("\\/", "/"));
                if (Files.exists(s)) {
                    found = Optional.of(s);
                }
            }
        }
        return found;
    }
}
