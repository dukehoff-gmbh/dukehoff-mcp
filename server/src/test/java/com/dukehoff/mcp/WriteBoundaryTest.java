/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class WriteBoundaryTest {

    @Test
    public void filesOutsideEveryRoot() {
        File ws = new File("/work/app");
        File inside = new File("/work/app/src/A.java");
        File sibling = new File("/work/app-extra/B.java"); // a name prefix is no parent
        File shared = new File("/work/shared/C.java");
        File single = new File("/work/other/D.java");
        assertEquals(List.of(sibling, shared, single), WriteBoundary.outside(List.of(inside, sibling, shared, single), List.of(ws)));
        assertEquals(List.of(sibling), WriteBoundary.outside(List.of(inside, sibling, shared, single),
                List.of(ws, new File("/work/shared"), single)));
        assertEquals(List.of(), WriteBoundary.outside(List.of(new File("/work/app/../app/x/Y.java")), List.of(ws)));
    }

    @Test
    public void allowWritesIsCommaSeparated() {
        assertEquals(List.of("../shared", "/opt/lib"), WriteBoundary.parse(" ../shared, ,/opt/lib "));
        assertEquals(List.of(), WriteBoundary.parse(null));
    }

    @Test
    public void theRegistrationsPathsAreSetPerCall() {
        assertEquals(List.of(), WriteBoundary.registered());
        WriteBoundary.set(List.of("../shared"));
        assertEquals(List.of("../shared"), WriteBoundary.registered());
        WriteBoundary.clear();
        assertEquals(List.of(), WriteBoundary.registered());
    }

    @Test
    public void stringsTakeAListOrOneString() {
        assertEquals(List.of("a", "b"), Json.strings(Map.of("k", List.of("a", " ", "b ")), "k"));
        assertEquals(List.of("a"), Json.strings(Map.of("k", "a"), "k"));
        assertEquals(List.of(), Json.strings(Map.of(), "k"));
    }
}
