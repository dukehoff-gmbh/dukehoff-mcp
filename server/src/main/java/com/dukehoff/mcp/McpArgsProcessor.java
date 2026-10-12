/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.netbeans.api.sendopts.CommandException;
import com.dukehoff.mcp.tools.ApplyRuleTool;
import com.dukehoff.mcp.tools.ChangeSignatureTool;
import com.dukehoff.mcp.tools.DebugTool;
import com.dukehoff.mcp.tools.DiagnosticsTool;
import com.dukehoff.mcp.tools.FindImplementationsTool;
import com.dukehoff.mcp.tools.FindSymbolTool;
import com.dukehoff.mcp.tools.FindUsagesTool;
import com.dukehoff.mcp.tools.InlineTool;
import com.dukehoff.mcp.tools.MoveTool;
import com.dukehoff.mcp.tools.OutlineTool;
import com.dukehoff.mcp.tools.RenameTool;
import com.dukehoff.mcp.tools.SafeDeleteTool;
import com.dukehoff.mcp.tools.WorkspaceStatusTool;
import org.netbeans.spi.sendopts.Arg;
import org.netbeans.spi.sendopts.ArgsProcessor;
import org.netbeans.spi.sendopts.Description;
import org.netbeans.spi.sendopts.Env;
import org.openide.LifecycleManager;
import org.openide.modules.ModuleInfo;
import org.openide.modules.Modules;
import org.openide.util.NbBundle.Messages;

/**
 * Command line entry point:
 * {@code netbeans --nogui --start-mcp-server [stdio] [--mcp-workspace <dir>] [--mcp-socket <path>]}.
 * The call blocks until the client closes stdin, or with {@code --mcp-socket} until the persistent
 * server stops ({@link Daemon}), then shuts NetBeans down.
 */
public final class McpArgsProcessor implements ArgsProcessor {

    private static final Logger LOG = Logger.getLogger(McpArgsProcessor.class.getName());
    /** How long NetBeans may take to exit before the VM is halted; a normal exit takes a few seconds. */
    private static final long EXIT_GRACE_SECONDS = 30;

    @Arg(longName = "start-mcp-server", defaultValue = "stdio")
    @Description(shortDescription = "#DESC_StartMcpServer")
    @Messages("DESC_StartMcpServer=Starts a Model Context Protocol (MCP) server for Java on stdio")
    public String transport;

    @Arg(longName = "mcp-workspace")
    @Description(shortDescription = "#DESC_McpWorkspace")
    @Messages("DESC_McpWorkspace=Folder with the project(s) the MCP server works on; defaults to the current directory")
    public String workspace;

    @Arg(longName = "mcp-tools")
    @Description(shortDescription = "#DESC_McpTools")
    @Messages("DESC_McpTools=Tools to offer: comma separated tool names, groups (core, refactor, migrate, debug, all) and presets (copilot, claude-code); default all")
    public String toolSelection;

    @Arg(longName = "mcp-socket")
    @Description(shortDescription = "#DESC_McpSocket")
    @Messages("DESC_McpSocket=Serves MCP clients on this Unix domain socket instead of stdio and stays running between them (persistent server)")
    public String socket;

    @Messages({
        "# {0} - transport",
        "ERR_UnsupportedTransport=Unsupported MCP transport ''{0}'', only ''stdio'' is available"
    })
    @Override
    public void process(Env env) throws CommandException {
        if (transport == null) {
            return;
        }
        if (!transport.isEmpty() && !"stdio".equals(transport)) {
            throw new CommandException(570, Bundle.ERR_UnsupportedTransport(transport));
        }
        File dir = workspace == null ? env.getCurrentDirectory() : new File(workspace);
        if (!dir.isAbsolute()) {
            dir = new File(env.getCurrentDirectory(), workspace);
        }

        InputStream protocolIn = env.getInputStream();
        OutputStream protocolOut = env.getOutputStream();
        // stdout now belongs to the protocol: anything else printed there would corrupt
        // the JSON-RPC stream, so send stray System.out output to stderr instead
        System.setOut(System.err);

        Workspace ws = new Workspace(dir);
        List<Tool> tools = tools(ws);
        List<Tool> selected;
        try {
            // the persistent server selects per connection; a per-session server here
            selected = socket != null ? tools : ToolSelection.select(tools, toolSelection);
        } catch (IllegalArgumentException ex) {
            throw new CommandException(572, ex.getMessage());
        }
        ws.openAsync();
        try {
            if (socket != null) {
                new Daemon(Paths.get(socket), dir, tools, version()).run();
            } else {
                new McpServer(protocolIn, protocolOut, selected).run();
            }
        } catch (IOException ex) {
            throw (CommandException) new CommandException(571).initCause(ex);
        } finally {
            // the client (or, for the persistent server, the last one) went away; don't leave a
            // headless NetBeans running
            haltIfStillRunning();
            LifecycleManager.getDefault().exit();
        }
    }

    /**
     * Implementation and build version of this module, as the launcher's proxy reads them from the
     * module's manifest: a daemon of another build is replaced.
     */
    static String version() {
        ModuleInfo module = Modules.getDefault().ownerOf(McpArgsProcessor.class);
        return module == null ? "unknown" : module.getImplementationVersion() + " " + module.getBuildVersion();
    }

    private static List<Tool> tools(Workspace ws) {
        return List.of(
                new FindSymbolTool(ws),
                new FindUsagesTool(ws),
                new FindImplementationsTool(ws),
                new OutlineTool(ws),
                new DiagnosticsTool(ws),
                new RenameTool(ws),
                new MoveTool(ws),
                new ChangeSignatureTool(ws),
                new SafeDeleteTool(ws),
                new InlineTool(ws),
                new ApplyRuleTool(ws),
                new DebugTool(ws),
                new WorkspaceStatusTool(ws));
    }

    /**
     * NetBeans sometimes stays alive after {@code exit()} (non-daemon threads, no exit in progress).
     * A leftover instance holds the userdir lock and would take over the next start, so give up
     * waiting after a grace period.
     */
    private static void haltIfStillRunning() {
        Thread watchdog = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(EXIT_GRACE_SECONDS);
            } catch (InterruptedException ex) {
                return;
            }
            LOG.warning("NetBeans did not exit within " + EXIT_GRACE_SECONDS + " s; halting the VM");
            Runtime.getRuntime().halt(0);
        }, "dukehoff-mcp exit watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
