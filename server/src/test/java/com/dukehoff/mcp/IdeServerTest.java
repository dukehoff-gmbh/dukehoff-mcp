/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class IdeServerTest {

    @Test
    public void removesTheEntriesAndSocketsOfEndedIdesOnly() throws Exception {
        Path registry = Files.createTempDirectory("ide-registry");
        // a process that has ended
        Process gone = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        gone.waitFor();
        Path ended = Files.writeString(registry.resolve(gone.pid() + ".json"), "{}");
        Path endedSocket = Files.createFile(registry.resolve(gone.pid() + ".sock"));
        long self = ProcessHandle.current().pid();
        Path running = Files.writeString(registry.resolve(self + ".json"), "{}");
        Path runningSocket = Files.createFile(registry.resolve(self + ".sock"));
        Path other = Files.writeString(registry.resolve("notes.json"), "{}");
        IdeServer.removeEnded(registry);
        assertFalse(Files.exists(ended));
        assertFalse(Files.exists(endedSocket));
        assertTrue(Files.exists(running));
        assertTrue(Files.exists(runningSocket));
        assertTrue("files not named by a pid stay", Files.exists(other));
    }
}
