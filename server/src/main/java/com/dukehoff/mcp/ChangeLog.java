/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The changes the tools wrote to disk, for an IDE to show for review: inside VS Code our extension asks
 * for them ({@link EmbeddedServer}) and offers a diff of each refactoring and to undo it. Records nothing
 * until {@link #enable()}: the standalone server has nobody to show them to.
 */
public final class ChangeLog {

    /**
     * A file a tool changed.
     *
     * @param oldFile the file before, null if the tool created it
     * @param newFile the file after, null if the tool deleted it; differs from oldFile when moved or renamed
     * @param before the content before, null for a created file
     * @param after the content after, null for a deleted file
     */
    public record FileChange(File oldFile, File newFile, String before, String after) {
    }

    /** One tool call's changes; {@code seq} counts from 1. */
    public record Entry(long seq, String summary, List<FileChange> files) {
    }

    /** Entries kept: an IDE that asked last before more calls than that misses the oldest. */
    private static final int KEPT = 20;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
    private static final List<CompletableFuture<List<Entry>>> WAITING = new ArrayList<>();
    private static boolean enabled;
    private static long seq;

    private ChangeLog() {
    }

    public static synchronized void enable() {
        enabled = true;
    }

    /** Records one tool call's changes, if enabled and there are any. */
    public static void record(String summary, List<FileChange> files) {
        List<CompletableFuture<List<Entry>>> wake;
        Entry entry;
        synchronized (ChangeLog.class) {
            if (!enabled || files.isEmpty()) {
                return;
            }
            entry = new Entry(++seq, summary, List.copyOf(files));
            ENTRIES.addLast(entry);
            while (ENTRIES.size() > KEPT) {
                ENTRIES.removeFirst();
            }
            wake = new ArrayList<>(WAITING);
            WAITING.clear();
        }
        for (CompletableFuture<List<Entry>> w : wake) {
            w.complete(List.of(entry));
        }
    }

    /**
     * The entries after {@code after}, as soon as there is one; an empty list after {@code timeoutMillis}
     * without any, so that the asking side can ask again.
     */
    public static CompletableFuture<List<Entry>> after(long after, long timeoutMillis) {
        synchronized (ChangeLog.class) {
            List<Entry> newer = ENTRIES.stream().filter(e -> e.seq() > after).toList();
            if (!newer.isEmpty()) {
                return CompletableFuture.completedFuture(newer);
            }
            CompletableFuture<List<Entry>> waiting = new CompletableFuture<>();
            WAITING.add(waiting);
            return waiting.completeOnTimeout(List.of(), timeoutMillis, TimeUnit.MILLISECONDS)
                    .whenComplete((r, t) -> {
                        synchronized (ChangeLog.class) {
                            WAITING.remove(waiting);
                        }
                    });
        }
    }

    /** An entry as JSON-friendly maps and lists (what the LSP command returns). */
    static Map<String, Object> toJson(Entry e) {
        List<Object> files = new ArrayList<>();
        for (FileChange f : e.files()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("oldPath", f.oldFile() == null ? null : f.oldFile().getPath());
            m.put("newPath", f.newFile() == null ? null : f.newFile().getPath());
            m.put("before", f.before());
            m.put("after", f.after());
            files.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq());
        m.put("summary", e.summary());
        m.put("files", files);
        return m;
    }
}
