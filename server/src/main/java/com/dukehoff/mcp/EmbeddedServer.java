/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.spi.lsp.CommandProvider;
import org.openide.filesystems.FileUtil;
import org.openide.modules.ModuleInfo;
import org.openide.modules.Modules;
import org.openide.util.Lookup;
import org.openide.util.lookup.ServiceProvider;

/**
 * The MCP server inside the NetBeans of the Apache NetBeans extension for VS Code (nbcode), which
 * loads this module as a cluster from the Dukehoff MCP extension. That extension calls the LSP command
 * {@value #CONNECTION}: the first call starts the server on a Unix domain socket and every call
 * returns its path; the extension then registers the launcher's proxy ({@code --connect <socket>
 * <workspace folder>}) as MCP server for Copilot. One server serves all workspace folders of the
 * VS Code window, each opened on its first connection; it ends with nbcode. Our own runtime has no
 * LSP server, so nothing calls the command there.
 * <p>
 * The command {@value #CHANGES} with the last sequence number seen returns the changes the tools wrote
 * since ({@link ChangeLog}), waiting up to {@value #CHANGES_WAIT_SECONDS} s for one: the extension shows
 * each refactoring as a diff and offers to undo it.
 */
@ServiceProvider(service = CommandProvider.class)
public final class EmbeddedServer implements CommandProvider {

    // NetBeans' LSP server requires its prefix nbls (and rewrites it to the client's, e.g. jdk for Oracle's extension)
    static final String CONNECTION = "nbls.dukehoff-mcp.connection";
    static final String CHANGES = "nbls.dukehoff-mcp.changes";
    /** Below the time a client waits for a command's answer, so the extension asks again instead. */
    static final int CHANGES_WAIT_SECONDS = 50;
    private static final Logger LOG = Logger.getLogger(EmbeddedServer.class.getName());
    private static final Map<File, List<Tool>> WORKSPACES = new ConcurrentHashMap<>();
    private static Path socket;
    private static Daemon daemon;

    @Override
    public Set<String> getCommands() {
        return Set.of(CONNECTION, CHANGES);
    }

    @Override
    public CompletableFuture<Object> runCommand(String command, List<Object> arguments) {
        if (javaSupportOff()) {
            // NetBeans then leaves Java to another extension: its refactoring engine finds no usages,
            // and our tools would answer "none" instead of failing
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "NetBeans' Java support is switched off (setting netbeans.javaSupport.enabled), so its refactoring "
                    + "engine finds nothing; enable it for Dukehoff MCP for Java"));
        }
        if (CHANGES.equals(command)) {
            ChangeLog.enable();
            long after = arguments.isEmpty() || arguments.get(0) == null ? 0 : sequence(arguments.get(0));
            return ChangeLog.after(after, CHANGES_WAIT_SECONDS * 1000L)
                    .thenApply(entries -> entries.stream().map(ChangeLog::toJson).toList());
        }
        try {
            Path s = start();
            // the command runs in the LSP client's context; connections are served in it, so that tools
            // see the projects opened for this VS Code window and the refactoring engine finds usages
            daemon.setContext(Lookup.getDefault());
            return CompletableFuture.completedFuture(Map.of("socket", s.toString(), "version", McpArgsProcessor.version()));
        } catch (IOException | InterruptedException ex) {
            LOG.log(Level.WARNING, "dukehoff-mcp: the embedded server did not start", ex);
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** A sequence number as it arrives from JSON: a number, or gson's primitive whose text is the number. */
    static long sequence(Object argument) {
        String text = argument instanceof Number n ? Long.toString(n.longValue()) : String.valueOf(argument).replace("\"", "");
        try {
            return (long) Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * The extension runs nbcode with its module nbcode.integration.java enabled when NetBeans' Java
     * support is off ({@code netbeans.javaSupport.enabled}), to coexist with another Java extension.
     */
    static boolean javaSupportOff() {
        ModuleInfo integration = Modules.getDefault().findCodeNameBase("org.netbeans.modules.nbcode.integration.java");
        return integration != null && integration.isEnabled();
    }

    /** Starts the server once; its socket. */
    static synchronized Path start() throws IOException, InterruptedException {
        if (socket != null) {
            return socket;
        }
        // a short path (Unix domain sockets allow ~100 bytes; nbcode's userdir is longer), owner only
        Path dir;
        try {
            dir = Files.createTempDirectory("dukehoff-mcp-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException ex) {
            dir = Files.createTempDirectory("dukehoff-mcp-"); // Windows: the user's temp directory is private
        }
        Path s = dir.resolve("embedded.sock");
        ChangeLog.enable();
        Daemon d = new Daemon(s, null, EmbeddedServer::toolsFor, McpArgsProcessor.version(), true);
        Thread t = new Thread(() -> {
            try {
                d.run();
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "dukehoff-mcp: the embedded server stopped", ex);
            }
        }, "dukehoff-mcp embedded server");
        t.setDaemon(true);
        t.start();
        if (!d.awaitListening(30)) {
            throw new IOException("the embedded server did not listen on " + s + " within 30 s");
        }
        daemon = d;
        socket = s;
        return s;
    }

    /** The tools for a workspace folder, opened on its first connection. */
    static List<Tool> toolsFor(String workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("the connection names no workspace folder (launcher: --connect <socket> <folder>)");
        }
        File root = FileUtil.normalizeFile(new File(workspace));
        if (!root.isDirectory()) {
            throw new IllegalArgumentException("not a folder: " + workspace);
        }
        return WORKSPACES.computeIfAbsent(root, r -> {
            Workspace ws = new Workspace(r, true);
            ws.openAsync();
            return McpArgsProcessor.tools(ws);
        });
    }
}
