/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class OldLombokTest {

    @Test
    public void olderLombokJarsAreTooOld() {
        assertEquals("lombok-1.18.38.jar", OldLombok.tooOld("lombok-1.18.38.jar"));
        assertEquals("lombok-1.18.46.jar", OldLombok.tooOld("lombok-1.18.46.jar"));
        assertEquals("lombok-1.16.99.jar", OldLombok.tooOld("lombok-1.16.99.jar"));
    }

    @Test
    public void currentLombokAndOtherJarsAreNot() {
        assertNull(OldLombok.tooOld("lombok-1.18.48.jar"));
        assertNull(OldLombok.tooOld("lombok-1.19.0.jar"));
        assertNull(OldLombok.tooOld("lombok-mapstruct-binding-0.2.0.jar"));
        assertNull(OldLombok.tooOld("lombok.jar"));
    }
}
