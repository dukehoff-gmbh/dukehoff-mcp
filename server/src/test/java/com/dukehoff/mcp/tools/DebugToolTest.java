/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class DebugToolTest {

    @Test
    public void binaryNameOfNestedClasses() {
        assertEquals("org.acme.Flow$VisitorImpl", DebugTool.binaryName("org.acme.Flow.VisitorImpl", "org.acme.Flow"));
        assertEquals("org.acme.Flow$A$B", DebugTool.binaryName("org.acme.Flow.A.B", "org.acme.Flow"));
        assertEquals("org.acme.Flow", DebugTool.binaryName("org.acme.Flow", "org.acme.Flow"));
    }

    @Test
    public void binaryNameWithoutTopLevelClass() {
        assertEquals("org.acme.Flow.VisitorImpl", DebugTool.binaryName("org.acme.Flow.VisitorImpl", (String) null));
        // a class merely sharing a prefix is no nested class
        assertEquals("org.acme.Flowing", DebugTool.binaryName("org.acme.Flowing", "org.acme.Flow"));
    }
}
