/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class StringMentionsTest {

    private static final String SOURCE = String.join("\n",
            "class T {",                                                          // 1
            "    // getFoo in a comment: \"getFoo\"",                              // 2
            "    /* \"getFoo\" in a block comment */",                             // 3
            "    Method m = C.class.getMethod(\"getFoo\", String.class);",         // 4
            "    String data = \"getFooBar test_getFoo\";",                        // 5
            "    char q = '\"'; String after = \"x\";",                             // 6
            "    String fqn = \"com.acme.C#getFoo(String)\";",                     // 7
            "    String esc = \"say \\\"getFoo\\\" twice: getFoo\";",              // 8
            "    String block = \"\"\"",                                           // 9
            "        call getFoo here",                                           // 10
            "        \"\"\";",                                                    // 11
            "    int getFoo() { return 0; }",                                     // 12
            "}");

    @Test
    public void findsTheNameInStringsOnly() {
        List<StringMentions.Hit> hits = StringMentions.scan(SOURCE, "getFoo");
        assertEquals("comments, longer identifiers and code are no hits",
                List.of(4, 7, 8, 9), hits.stream().map(StringMentions.Hit::line).toList());
        assertEquals("Method m = C.class.getMethod(\"getFoo\", String.class);", hits.get(0).source());
    }

    @Test
    public void oneHitPerLine() {
        List<StringMentions.Hit> hits = StringMentions.scan("a(\"Foo\", \"x.Foo\");\nb(\"Foo\");", "Foo");
        assertEquals(List.of(1, 2), hits.stream().map(StringMentions.Hit::line).toList());
    }

    @Test
    public void nothingWithoutTheName() {
        assertTrue(StringMentions.scan(SOURCE, "absent").isEmpty());
        assertTrue(StringMentions.scan("String s = \"unterminated\nint getFoo;", "getFoo").isEmpty());
    }

    @Test
    public void reportsBoundedByFile() {
        Map<String, List<StringMentions.Hit>> found = new LinkedHashMap<>();
        assertEquals("", StringMentions.report(found, "getFoo"));
        found.put("src/A.java", List.of(new StringMentions.Hit(4, "a")));
        String one = StringMentions.report(found, "getFoo");
        assertTrue(one, one.contains("still contain \"getFoo\": 1 place in 1 file") && one.contains("src/A.java\n  4: a\n"));
        found.put("src/B.java", java.util.Collections.nCopies(StringMentions.LIMIT + 5, new StringMentions.Hit(1, "b")));
        String many = StringMentions.report(found, "getFoo");
        assertTrue(many, many.contains(StringMentions.LIMIT + 6 + " places in 2 files") && many.contains("... and 6 more"));
    }
}
