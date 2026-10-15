/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

public class IdeRegistryTest {

    @Test
    public void findsARunningIdeAndRemovesEntriesOfEndedOnes() throws Exception {
        Path dir = Files.createTempDirectory("ide-registry");
        Path socket = Files.createFile(dir.resolve("ide.sock"));
        // a process that has ended
        Process gone = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        gone.waitFor();
        Path ended = dir.resolve(gone.pid() + ".json");
        Files.writeString(ended, "{\"socket\":\"" + json(socket.toString()) + "\",\"pid\":" + gone.pid() + "}\n");
        Path running = dir.resolve(ProcessHandle.current().pid() + ".json");
        Files.writeString(running, "{\"socket\":\"" + json(socket.toString()) + "\",\"pid\":" + ProcessHandle.current().pid() + "}\n");
        assertEquals(Optional.of(socket), IdeRegistry.find(dir));
        assertFalse("the entry of the ended process is gone", Files.exists(ended));
        assertTrue(Files.exists(running));
    }

    @Test
    public void nothingWithoutTheRegistry() throws Exception {
        assertEquals(Optional.empty(), IdeRegistry.find(Files.createTempDirectory("ide-registry").resolve("missing")));
        assertEquals(Path.of("/x"), IdeRegistry.dir(Map.of("DUKEHOFF_MCP_IDE_DIR", "/x")));
    }

    /** As JSONValue writes it: backslashes and slashes escaped. */
    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("/", "\\/");
    }
}
