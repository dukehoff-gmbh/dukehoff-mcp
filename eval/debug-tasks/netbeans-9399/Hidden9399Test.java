/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * Hidden check of eval task 10/11 (NetBeans #9399); copied into the module's tests by check.sh and
 * removed afterwards.
 */
package org.netbeans.modules.java.hints.bugs;

import org.netbeans.junit.NbTestCase;
import org.netbeans.modules.java.hints.test.api.HintTest;

public class Hidden9399Test extends NbTestCase {

    public Hidden9399Test(String name) {
        super(name);
    }

    public void testBlockBodiedArms() throws Exception {
        HintTest
                .create()
                .input("package test;\n" +
                       "public class Test {\n" +
                       "    public void f(Object o) {\n" +
                       "        String data = null;\n" +
                       "        if (o != null) {\n" +
                       "            data = switch (o) {\n" +
                       "                case Integer i -> { yield \"i\"; }\n" +
                       "                default -> { throw new IllegalArgumentException(); }\n" +
                       "            };\n" +
                       "        }\n" +
                       "        System.err.println(data);\n" +
                       "    }\n" +
                       "}\n")
                .sourceLevel("21")
                .run(UnusedAssignmentOrBranch.class)
                .assertWarnings();
    }

    public void testAllArmsThrowStillExits() throws Exception {
        HintTest
                .create()
                .input("package test;\n" +
                       "public class Test {\n" +
                       "    public void f(int k) {\n" +
                       "        String data = \"x\";\n" +
                       "        if (k > 0) {\n" +
                       "            data = \"y\";\n" +
                       "            switch (k) {\n" +
                       "                case 1 -> throw new IllegalStateException();\n" +
                       "                default -> throw new IllegalArgumentException();\n" +
                       "            }\n" +
                       "        }\n" +
                       "        System.err.println(data);\n" +
                       "    }\n" +
                       "}\n")
                .sourceLevel("21")
                .run(UnusedAssignmentOrBranch.class)
                .assertWarnings("5:19-5:22:verifier:LBL_UNUSED_ASSIGNMENT_LABEL");
    }
}
