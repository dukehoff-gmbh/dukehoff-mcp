/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class UnifiedDiffTest {

    @Test
    public void identicalGivesNothing() {
        assertEquals("", UnifiedDiff.diff("a.txt", "a.txt", "x\ny\n", "x\ny\n"));
    }

    @Test
    public void changedLineWithContext() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n";
        String after = "1\n2\n3\n4\nfive\n6\n7\n8\n9\n";
        assertEquals("""
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -2,7 +2,7 @@
                 2
                 3
                 4
                -5
                +five
                 6
                 7
                 8
                """, UnifiedDiff.diff("a.txt", "a.txt", before, after));
    }

    @Test
    public void absolutePathsGetNoPrefix() {
        assertEquals("""
                diff --git /p/A.java /p/B.java
                rename from /p/A.java
                rename to /p/B.java
                --- /p/A.java
                +++ /p/B.java
                @@ -1,1 +1,1 @@
                -class A {}
                +class B {}
                """, UnifiedDiff.diff("/p/A.java", "/p/B.java", "class A {}\n", "class B {}\n"));
        assertEquals("diff --git C:\\p\\A.java C:\\p\\A.java\ndeleted file mode 100644\n",
                UnifiedDiff.deletion("C:\\p\\A.java", ""));
    }

    @Test
    public void distantChangesGetSeparateHunks() {
        String before = "a\n1\n2\n3\n4\n5\n6\n7\n8\nb\n";
        String after = "A\n1\n2\n3\n4\n5\n6\n7\n8\nB\n";
        String diff = UnifiedDiff.diff("f", "f", before, after);
        assertEquals(2, diff.split("\n@@ ", -1).length - 1);
    }

    @Test
    public void insertionIntoEmptyFile() {
        assertEquals("""
                diff --git a/n b/n
                --- a/n
                +++ b/n
                @@ -0,0 +1,2 @@
                +x
                +y
                """, UnifiedDiff.diff("n", "n", "", "x\ny\n"));
    }

    @Test
    public void missingFinalNewlineIsAChange() {
        assertEquals("""
                diff --git a/f b/f
                --- a/f
                +++ b/f
                @@ -1,2 +1,2 @@
                 a
                -b
                +b
                \\ No newline at end of file
                """, UnifiedDiff.diff("f", "f", "a\nb\n", "a\nb"));
    }

    @Test
    public void deletedFile() {
        assertEquals("""
                diff --git a/p/Gone.java b/p/Gone.java
                deleted file mode 100644
                --- a/p/Gone.java
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -class Gone {
                -}
                """, UnifiedDiff.deletion("p/Gone.java", "class Gone {\n}\n"));
    }

    @Test
    public void renameWithAndWithoutChanges() {
        assertEquals("""
                diff --git a/p/Old.java b/p/New.java
                rename from p/Old.java
                rename to p/New.java
                """, UnifiedDiff.diff("p/Old.java", "p/New.java", "class X {}\n", "class X {}\n"));
        String diff = UnifiedDiff.diff("p/Old.java", "p/New.java", "class Old {}\n", "class New {}\n");
        assertEquals("""
                diff --git a/p/Old.java b/p/New.java
                rename from p/Old.java
                rename to p/New.java
                --- a/p/Old.java
                +++ b/p/New.java
                @@ -1,1 +1,1 @@
                -class Old {}
                +class New {}
                """, diff);
    }
}
