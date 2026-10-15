/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.util.List;
import java.util.Map;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Tool;
import com.dukehoff.mcp.Workspace;
import org.openide.filesystems.FileObject;

/**
 * Reports what the server has opened and whether it is ready.
 */
public final class WorkspaceStatusTool implements Tool {

    /** Upper bound for 'wait', in seconds: the other tools' wait, which stays below the clients' timeouts. */
    private static final int MAX_WAIT = Workspace.WAIT_SECONDS;

    private final Workspace workspace;

    public WorkspaceStatusTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "workspace_status";
    }

    @Override
    public String description() {
        return "Show the state of the Java workspace: opened projects, Java source roots and whether "
                + "indexing is still running. The other tools wait for the workspace themselves (up to "
                + MAX_WAIT + " s, then they say to retry), so there is no need to poll; to wait here, pass "
                + "'wait' (seconds): the call returns as soon as the workspace is ready.";
    }

    @Override
    public List<String> searchTerms() {
        return List.of("status", "indexing", "projects", "source roots");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj("type", "object", "properties", Json.obj(
                "wait", Json.obj("type", "integer",
                        "description", "Seconds to wait for the workspace to be ready, at most " + MAX_WAIT + "; default 0")));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws InterruptedException {
        int wait = Math.max(0, Math.min(MAX_WAIT, Json.integer(arguments, "wait", 0)));
        if (wait > 0) {
            workspace.awaitReady(wait);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Workspace: ").append(workspace.describe()).append('\n');
        sb.append("State: ").append(workspace.getPhase())
                .append(workspace.isIndexing() ? " (indexing in progress)" : "").append('\n');
        List<Project> projects = workspace.getProjects();
        if (!projects.isEmpty()) {
            sb.append("Projects:\n");
            for (Project p : projects) {
                sb.append("  ").append(ProjectUtils.getInformation(p).getDisplayName())
                        .append("  ").append(workspace.displayPath(p.getProjectDirectory())).append('\n');
            }
            sb.append("Java source roots:\n");
            for (FileObject root : Workspace.javaSourceRoots(projects)) {
                sb.append("  ").append(workspace.displayPath(root)).append('\n');
            }
        }
        return Result.ok(sb.toString());
    }
}
