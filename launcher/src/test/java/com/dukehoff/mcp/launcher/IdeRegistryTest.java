/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

public class IdeRegistryTest {

    @Test
    public void findsTheNewestIdeThatAnswersAndRemovesNothing() throws Exception {
        Path dir = Files.createTempDirectory("ide-registry");
        Path live = dir.resolve("1.sock");
        try (ServerSocketChannel server = listen(live)) {
            // an older IDE that answers, with a pid nobody here can see (a sandbox's own process namespace)
            Path running = entry(dir, "999999999", live, 1_000);
            // a newer one whose socket is left over: nothing answers on it
            Path dead = Files.createFile(dir.resolve("2.sock"));
            Path ended = entry(dir, "2", dead, 2_000);
            // a newer one whose socket does not exist
            Path gone = entry(dir, "3", dir.resolve("3.sock"), 3_000);
            assertEquals(Optional.of(live), IdeRegistry.find(dir));
            assertTrue("the launcher removes nothing (the IDE cleans up)",
                    Files.exists(running) && Files.exists(ended) && Files.exists(gone));
        }
    }

    @Test
    public void readsARegistryItCannotWrite() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path dir = Files.createTempDirectory("ide-registry");
        Path live = dir.resolve("1.sock");
        try (ServerSocketChannel server = listen(live)) {
            entry(dir, "999999999", live, 1_000);
            entry(dir, "2", dir.resolve("2.sock"), 2_000);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
            try {
                assertEquals(Optional.of(live), IdeRegistry.find(dir));
            } finally {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    @Test
    public void nothingWithoutTheRegistry() throws Exception {
        assertEquals(Optional.empty(), IdeRegistry.find(Files.createTempDirectory("ide-registry").resolve("missing")));
        assertEquals(Path.of("/x"), IdeRegistry.dir(Map.of("DUKEHOFF_MCP_IDE_DIR", "/x")));
    }

    private static ServerSocketChannel listen(Path socket) throws Exception {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        return server;
    }

    /** An entry as the IDE writes it, with its age (newest first decides). */
    private static Path entry(Path dir, String pid, Path socket, long millis) throws Exception {
        Path entry = dir.resolve(pid + ".json");
        Files.writeString(entry, "{\"socket\":\"" + json(socket.toString()) + "\",\"pid\":" + pid + "}\n");
        Files.setLastModifiedTime(entry, FileTime.fromMillis(millis));
        return entry;
    }

    /** As JSONValue writes it: backslashes and slashes escaped. */
    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("/", "\\/");
    }
}
