/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Tool.Result;
import com.dukehoff.mcp.Workspace;
import org.openide.filesystems.FileObject;

/**
 * The {@code symbol} argument of a tool call resolved against the workspace:
 * either the source roots and the symbol, or an error result to return.
 */
record Resolution(List<FileObject> roots, SymbolResolver.Resolved symbol, Result error) {

    static Resolution of(Workspace workspace, Map<?, ?> arguments) throws IOException {
        String text = Json.string(arguments, "symbol");
        if (text == null) {
            return failed("'symbol' is required");
        }
        SymbolSpec spec;
        try {
            spec = SymbolSpec.parse(text);
        } catch (IllegalArgumentException ex) {
            return failed(ex.getMessage());
        }
        try {
            List<FileObject> roots = workspace.awaitSourceRoots();
            SymbolResolver.Resolved symbol = SymbolResolver.resolve(spec, roots);
            return symbol.generated() ? failed(symbol.generatedError()) : new Resolution(roots, symbol, null);
        } catch (Workspace.NotReadyException | SymbolResolver.ResolutionException ex) {
            return failed(ex.getMessage());
        }
    }

    private static Resolution failed(String message) {
        return new Resolution(null, null, Result.error(message));
    }
}
