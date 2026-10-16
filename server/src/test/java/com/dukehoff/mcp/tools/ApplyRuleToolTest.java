/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ApplyRuleToolTest {

    @Test
    public void validRulesPass() {
        assertNull(ApplyRuleTool.checkSyntax("$s.length() == 0 :: $s instanceof java.lang.String => $s.isEmpty() ;;"));
        assertNull(ApplyRuleTool.checkSyntax("new java.lang.Integer($v) => java.lang.Integer.valueOf($v) ;;"));
        assertNull(ApplyRuleTool.checkSyntax("java.util.Objects.equals($a, $b) ;;"));
        assertNull(ApplyRuleTool.checkSyntax("$list.foo($args$) => $list.bar($args$) ;; $x == null ;;"));
        assertNull(ApplyRuleTool.checkSyntax("if ($c) { $then; } else { $else; } => if (!$c) { $else; } else { $then; } ;;"));
    }

    @Test
    public void brokenPatternIsReported() {
        String message = ApplyRuleTool.checkSyntax("$a.foo((( => bar ;;");
        assertNotNull(message);
        assertTrue(message, message.contains("$a.foo((("));
    }

    @Test
    public void brokenReplacementIsReported() {
        String message = ApplyRuleTool.checkSyntax("$a.foo() => $a.bar(( ;;");
        assertNotNull(message);
        assertTrue(message, message.contains("$a.bar(("));
    }

    @Test
    public void everyPackLoadsAndParses() throws Exception {
        for (String name : ApplyRuleTool.PACKS.keySet()) {
            ApplyRuleTool.Pack pack = ApplyRuleTool.Pack.load(name);
            assertNotNull(name, pack);
            assertTrue(name + " has rules", pack.rules().split(";;").length > 5);
            assertTrue(name + " has notes", !pack.notes().isEmpty());
            assertTrue(name + ": no comment left in the rules", !pack.rules().contains("#"));
            assertNull(name, ApplyRuleTool.checkSyntax(pack.rules()));
        }
        assertNull(ApplyRuleTool.Pack.load("../junit5"));
        assertNull(ApplyRuleTool.Pack.load("nonsense"));
    }
}
