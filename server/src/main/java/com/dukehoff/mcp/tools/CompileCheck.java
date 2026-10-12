/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import com.dukehoff.mcp.Offered;
import com.dukehoff.mcp.Workspace;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.java.source.SourceUtils;
import org.netbeans.api.lsp.Diagnostic;
import org.netbeans.modules.parsing.spi.indexing.ErrorsCache;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.URLMapper;

/**
 * The compile check after a refactoring: which errors the refactoring introduced. Errors that were
 * there before are not reported, so an agent working in a partly broken project is not sent after
 * problems it did not cause.
 * <p>
 * Before the change it records the error messages of the files about to change (as text: lines move)
 * and which files NetBeans' error index has in error. Afterwards it checks the changed files again and
 * every file that newly is in error: a refactoring can break files it did not touch (a method made
 * private that others call).
 */
final class CompileCheck {

    private static final Logger LOG = Logger.getLogger(CompileCheck.class.getName());
    /** At most this many new errors are listed. */
    private static final int LIMIT = 30;
    /**
     * Files already in error are rechecked (their messages compared) up to this many; a refactoring can
     * add an error to a file that had one before. Beyond that a very broken workspace costs too much.
     */
    private static final int RECHECK_LIMIT = 50;

    private final Workspace workspace;
    private final List<FileObject> roots;
    private final Set<URL> inErrorBefore;
    private final Map<URL, List<String>> messagesBefore = new HashMap<>();

    private CompileCheck(Workspace workspace, List<FileObject> roots, Set<URL> inErrorBefore) {
        this.workspace = workspace;
        this.roots = roots;
        this.inErrorBefore = inErrorBefore;
    }

    /** Records the state before the refactoring changes {@code files}. */
    static CompileCheck before(Workspace workspace, List<FileObject> roots, Iterable<FileObject> files) throws IOException {
        CompileCheck check = new CompileCheck(workspace, roots, filesInError(roots));
        for (FileObject fo : files) {
            check.record(fo);
        }
        int rechecked = 0;
        for (URL u : check.inErrorBefore) {
            if (!check.messagesBefore.containsKey(u) && rechecked++ < RECHECK_LIMIT) {
                check.record(URLMapper.findFileObject(u));
            }
        }
        return check;
    }

    private void record(FileObject fo) {
        if (fo != null && fo.isValid() && fo.isData() && "java".equals(fo.getExt())) {
            List<String> messages = new ArrayList<>();
            for (Diagnostic d : DiagnosticsTool.diagnose(fo, false)) {
                messages.add(DiagnosticsTool.message(d));
            }
            messagesBefore.put(fo.toURL(), messages);
        }
    }

    /**
     * The result line(s) after the refactoring.
     *
     * @param changed old URL -> URL after the change (null if deleted) of every changed file
     */
    String after(Map<URL, URL> changed) throws IOException {
        // the index of the changed files is current (RefactoringRunner waited), but files depending on
        // them are rechecked in the background
        long start = System.currentTimeMillis();
        try {
            SourceUtils.waitScanFinished();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "\nCompile check: interrupted.\n";
        }
        long scanned = System.currentTimeMillis();
        Map<String, List<String>> fresh = new TreeMap<>();
        int newErrors = 0;
        Set<URL> checked = new LinkedHashSet<>();
        for (Map.Entry<URL, URL> c : changed.entrySet()) {
            FileObject fo = c.getValue() == null ? null : URLMapper.findFileObject(c.getValue());
            if (fo == null || !fo.isData() || !"java".equals(fo.getExt())) {
                continue;
            }
            checked.add(c.getKey());
            checked.add(c.getValue());
            newErrors += collect(fo, messagesBefore.getOrDefault(c.getKey(), List.of()), fresh);
        }
        int notRechecked = 0;
        for (URL u : filesInError(roots)) {
            if (checked.contains(u)) {
                continue;
            }
            if (inErrorBefore.contains(u) && !messagesBefore.containsKey(u)) {
                notRechecked++;
                continue;
            }
            FileObject fo = URLMapper.findFileObject(u);
            if (fo != null && fo.isData()) {
                // new in error, or in error before: then only messages it did not have count
                newErrors += collect(fo, messagesBefore.getOrDefault(u, List.of()), fresh);
            }
        }
        LOG.log(Level.INFO, "dukehoff-mcp timing: compile check {0} {1} {2}",
                new Object[] {String.valueOf(start), String.valueOf(scanned), String.valueOf(System.currentTimeMillis())});
        StringBuilder sb = new StringBuilder("\nCompile check: ");
        if (newErrors == 0) {
            sb.append("no new errors (").append(changed.size()).append(changed.size() == 1 ? " changed file" : " changed files")
                    .append(" checked, the rest by NetBeans' error index).");
        } else {
            sb.append(newErrors).append(newErrors == 1 ? " new error" : " new errors").append(" after the change");
            int shown = 0;
            outer:
            for (Map.Entry<String, List<String>> f : fresh.entrySet()) {
                sb.append("\n\n").append(f.getKey());
                for (String line : f.getValue()) {
                    if (shown++ == LIMIT) {
                        sb.append("\n... ").append(newErrors - LIMIT).append(" more")
                                .append(Offered.has("diagnostics") ? ", see diagnostics" : "");
                        break outer;
                    }
                    sb.append("\n  ").append(line);
                }
            }
        }
        int oldErrors = inErrorBefore.size();
        if (oldErrors > 0) {
            sb.append("\nErrors that were there before the change are not reported (")
                    .append(oldErrors).append(oldErrors == 1 ? " file was" : " files were").append(" in error");
            if (notRechecked > 0) {
                sb.append("; ").append(notRechecked).append(" of them not rechecked");
            }
            sb.append(Offered.has("diagnostics") ? "; see diagnostics)." : ").");
        }
        return sb.append('\n').toString();
    }

    /** Adds the errors of {@code fo} not in {@code before} (each message counted) to {@code into}. */
    private int collect(FileObject fo, List<String> before, Map<String, List<String>> into) {
        List<Diagnostic> errors = DiagnosticsTool.diagnose(fo, false);
        List<String> remaining = new ArrayList<>(before);
        List<Diagnostic> added = new ArrayList<>();
        for (Diagnostic d : errors) {
            if (!remaining.remove(DiagnosticsTool.message(d))) {
                added.add(d);
            }
        }
        if (!added.isEmpty()) {
            into.put(workspace.displayPath(fo), DiagnosticsTool.format(fo, added));
        }
        return added.size();
    }

    private static Set<URL> filesInError(List<FileObject> roots) throws IOException {
        Set<URL> urls = new LinkedHashSet<>();
        for (FileObject root : roots) {
            urls.addAll(ErrorsCache.getAllFilesInError(root.toURL()));
        }
        return urls;
    }
}
