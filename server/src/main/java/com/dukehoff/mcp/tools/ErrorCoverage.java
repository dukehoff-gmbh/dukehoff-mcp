/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.netbeans.modules.parsing.spi.indexing.ErrorsCache;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.URLMapper;

/**
 * Where semantic results may be missing: files with compile errors that mention a name. Code that
 * does not compile (often because a dependency is missing from its module's classpath) cannot be
 * attributed, so a usage there may not be found, and a result must not claim to be complete.
 */
final class ErrorCoverage {

    private ErrorCoverage() {
    }

    /** Files in error, in the given roots, whose text contains {@code name} as a word. */
    static List<FileObject> suspects(List<FileObject> roots, String name) throws IOException {
        Pattern word = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
        List<FileObject> found = new ArrayList<>();
        for (FileObject root : roots) {
            for (URL u : ErrorsCache.getAllFilesInError(root.toURL())) {
                FileObject fo = URLMapper.findFileObject(u);
                if (fo != null && fo.isData() && word.matcher(fo.asText()).find()) {
                    found.add(fo);
                }
            }
        }
        return found;
    }
}
