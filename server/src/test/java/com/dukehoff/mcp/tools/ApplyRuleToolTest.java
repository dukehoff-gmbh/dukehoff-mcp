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
}
