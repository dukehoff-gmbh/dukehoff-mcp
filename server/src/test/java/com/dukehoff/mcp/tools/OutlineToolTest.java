/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class OutlineToolTest {

    @Test
    public void simpleTypesDropsPackages() {
        assertEquals("String", OutlineTool.simpleTypes("java.lang.String"));
        assertEquals("List<Order.Line>", OutlineTool.simpleTypes("java.util.List<com.acme.shop.Order.Line>"));
        assertEquals("Map<String,List<Foo>>", OutlineTool.simpleTypes("java.util.Map<java.lang.String,java.util.List<a.b.Foo>>"));
    }

    @Test
    public void simpleTypesKeepsPrimitivesArraysAndWildcards() {
        assertEquals("int", OutlineTool.simpleTypes("int"));
        assertEquals("String[]", OutlineTool.simpleTypes("java.lang.String[]"));
        assertEquals("List<? extends Number>", OutlineTool.simpleTypes("java.util.List<? extends java.lang.Number>"));
        assertEquals("T", OutlineTool.simpleTypes("T"));
    }
}
