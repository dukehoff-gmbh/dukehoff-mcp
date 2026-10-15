/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ChangeLogTest {

    private static List<ChangeLog.FileChange> oneFile(String after) {
        File f = new File("/p/A.java");
        return List.of(new ChangeLog.FileChange(f, f, "class A {}", after));
    }

    @Test
    public void recordsOnlyWhenEnabledAndWakesWhoWaits() throws Exception {
        ChangeLog.record("before enabling", oneFile("class A { }"));
        assertTrue(ChangeLog.after(0, 100).get(5, TimeUnit.SECONDS).isEmpty());

        ChangeLog.enable();
        CompletableFuture<List<ChangeLog.Entry>> waiting = ChangeLog.after(0, 10_000);
        assertFalse(waiting.isDone());
        ChangeLog.record("rename A", oneFile("class B {}"));
        List<ChangeLog.Entry> entries = waiting.get(5, TimeUnit.SECONDS);
        assertEquals(1, entries.size());
        ChangeLog.Entry first = entries.get(0);
        assertEquals("rename A", first.summary());

        ChangeLog.record("nothing", List.of());
        ChangeLog.record("rename B", oneFile("class C {}"));
        assertEquals(List.of("rename A", "rename B"),
                ChangeLog.after(first.seq() - 1, 100).get(5, TimeUnit.SECONDS).stream().map(ChangeLog.Entry::summary).toList());
        assertEquals(1, ChangeLog.after(first.seq(), 100).get(5, TimeUnit.SECONDS).size());

        Map<String, Object> json = ChangeLog.toJson(first);
        assertEquals("rename A", json.get("summary"));
        @SuppressWarnings("unchecked")
        Map<String, Object> file = ((List<Map<String, Object>>) json.get("files")).get(0);
        assertEquals(new File("/p/A.java").getPath(), file.get("newPath"));
        assertEquals("class A {}", file.get("before"));
        assertEquals("class B {}", file.get("after"));
    }

    @Test
    public void anEmptyAnswerAfterTheTimeout() throws Exception {
        assertTrue(ChangeLog.after(Long.MAX_VALUE, 50).get(5, TimeUnit.SECONDS).isEmpty());
    }
}
