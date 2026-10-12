/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.JavaSource;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Offered;
import com.dukehoff.mcp.Tool;
import com.dukehoff.mcp.Workspace;
import org.openide.filesystems.FileObject;

/**
 * Lists the members of a type, or of all types in a file: modifiers,
 * canonical signatures, declared types and line numbers, without bodies.
 */
public final class OutlineTool implements Tool {

    private static final int DEFAULT_LIMIT = 300;
    // package prefixes in type names: java.util.List<com.acme.Foo> -> List<Foo>
    private static final Pattern PACKAGE_PREFIX = Pattern.compile("\\b(?:[a-z_$][\\w$]*\\.)+(?=[A-Za-z_$])");

    private final Workspace workspace;

    public OutlineTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "outline";
    }

    @Override
    public String description() {
        List<String> users = new ArrayList<>();
        for (String t : List.of("find_usages", "find_implementations")) {
            if (Offered.has(t)) {
                users.add(t);
            }
        }
        return "Show the structure of a Java type or file without reading it: nested types, fields, "
                + "constructors and methods with modifiers, declared types and line numbers. Each member is "
                + "listed with its canonical symbol (e.g. com.acme.Foo#bar(java.lang.String))"
                + (users.isEmpty() ? " for use with the other tools" : " for use with " + String.join(" and ", users))
                + ". Cheaper than reading the file when you only need the "
                + "API or want to know which lines to read. Give either 'symbol' (fully qualified type, "
                + "e.g. com.acme.Foo or com.acme.Foo.Inner) or 'file' (path relative to the workspace).";
    }

    @Override
    public List<String> searchTerms() {
        return List.of("class structure", "members", "list methods", "type API", "file structure");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Type, e.g. com.acme.OrderService; the package may be left out when the type name is unique"),
                        "file", Json.obj("type", "string",
                                "description", "Java source file, relative to the workspace or absolute"),
                        "limit", Json.obj("type", "integer",
                                "description", "Maximum members listed, default " + DEFAULT_LIMIT)));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String symbolText = Json.string(arguments, "symbol");
        String path = Json.string(arguments, "file");
        if ((symbolText == null) == (path == null)) {
            return Result.error("Give exactly one of 'symbol' (a fully qualified type) or 'file'.");
        }
        int limit = Json.integer(arguments, "limit", DEFAULT_LIMIT);
        List<FileObject> roots;
        try {
            roots = workspace.awaitSourceRoots();
        } catch (Workspace.NotReadyException ex) {
            return Result.error(ex.getMessage());
        }

        String typeName = null;
        FileObject file;
        if (symbolText != null) {
            SymbolSpec spec;
            try {
                spec = SymbolSpec.parse(symbolText);
            } catch (IllegalArgumentException ex) {
                return Result.error(ex.getMessage());
            }
            if (spec.member() != null) {
                return Result.error("outline takes a type, not a member; use " + spec.typeName());
            }
            typeName = spec.typeName();
            file = SymbolResolver.findSourceFile(typeName, roots);
            if (file == null) {
                // a simple or partly qualified name, as in the other tools
                try {
                    typeName = SymbolResolver.qualify(typeName, roots);
                } catch (SymbolResolver.ResolutionException ex) {
                    return Result.error(ex.getMessage());
                }
                file = SymbolResolver.findSourceFile(typeName, roots);
                if (file == null) {
                    return Result.error("Cannot find the source of " + typeName);
                }
            }
        } else {
            file = workspace.findFile(path);
            if (file == null || !file.isData()) {
                return Result.error("No such file: " + path);
            }
        }
        JavaSource js = JavaSource.forFileObject(file);
        if (js == null) {
            return Result.error("Not a Java source file: " + workspace.displayPath(file));
        }
        String requestedType = typeName;
        FileObject source = file;
        AtomicReference<Result> result = new AtomicReference<>();
        js.runUserActionTask(cc -> {
            cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
            List<TypeElement> types = new ArrayList<>();
            if (requestedType != null) {
                TypeElement type = cc.getElements().getTypeElement(requestedType);
                if (type == null) {
                    result.set(Result.error("Type " + requestedType + " not found in " + workspace.displayPath(source)));
                    return;
                }
                types.add(type);
            } else {
                types.addAll(cc.getTopLevelElements());
            }
            Printer p = new Printer(cc, limit);
            p.sb.append(workspace.displayPath(source)).append('\n');
            for (TypeElement t : types) {
                p.type(t);
            }
            result.set(Result.ok(p.finish()));
        }, true);
        return result.get();
    }

    static String simpleTypes(String type) {
        return PACKAGE_PREFIX.matcher(type).replaceAll("");
    }

    private static final class Printer {

        private final CompilationController cc;
        private final int limit;
        private final StringBuilder sb = new StringBuilder();
        private int members;
        private int omitted;

        Printer(CompilationController cc, int limit) {
            this.cc = cc;
            this.limit = limit;
        }

        void type(TypeElement type) {
            sb.append('\n').append(kind(type)).append(' ').append(type.getQualifiedName());
            String supers = supertypes(type);
            if (!supers.isEmpty()) {
                sb.append(supers);
            }
            int line = line(type);
            // a type an annotation processor generated (Lombok's builder) has no source line
            sb.append(line > 0 ? "  (line " + line + ")\n" : "  (generated)\n");
            List<TypeElement> nested = new ArrayList<>();
            for (Element e : type.getEnclosedElements()) {
                if (e instanceof TypeElement te) {
                    nested.add(te);
                }
                member(e);
            }
            for (TypeElement te : nested) {
                type(te);
            }
        }

        private void member(Element e) {
            if (members++ >= limit) {
                omitted++;
                return;
            }
            boolean implicit = cc.getElements().getOrigin(e) != Elements.Origin.EXPLICIT;
            int line = implicit ? 0 : line(e);
            if (line == 0 && e.getKind() == ElementKind.RECORD_COMPONENT) {
                line = line(e.getEnclosingElement()); // declared in the record header, which has no tree of its own
            }
            // no source line and not implicit: generated, by the compiler (record accessors, toString, ...)
            // or an annotation processor (Lombok's getters, builders)
            boolean generated = !implicit && line == 0;
            sb.append("  ").append(implicit || generated ? "-" : String.valueOf(line)).append(": ");
            String mods = e.getModifiers().stream().map(m -> m.toString()).collect(Collectors.joining(" "));
            if (!mods.isEmpty()) {
                sb.append(mods).append(' ');
            }
            sb.append(kind(e)).append(' ').append(SymbolResolver.signature(e, cc));
            switch (e.getKind()) {
                case FIELD, ENUM_CONSTANT, RECORD_COMPONENT ->
                    sb.append(" : ").append(simpleTypes(e.asType().toString()));
                case METHOD -> {
                    ExecutableElement ee = (ExecutableElement) e;
                    sb.append(" : ").append(simpleTypes(ee.getReturnType().toString()));
                    if (!ee.getThrownTypes().isEmpty()) {
                        sb.append(" throws ").append(ee.getThrownTypes().stream()
                                .map(t -> simpleTypes(t.toString())).collect(Collectors.joining(", ")));
                    }
                }
                default -> {
                }
            }
            if (implicit) {
                sb.append("  (implicit)");
            } else if (generated) {
                sb.append("  (generated)");
            }
            sb.append('\n');
        }

        private String supertypes(TypeElement type) {
            List<String> names = new ArrayList<>();
            if (type.getSuperclass().getKind() == TypeKind.DECLARED
                    && !"java.lang.Object".equals(type.getSuperclass().toString())
                    && type.getKind() == ElementKind.CLASS) {
                names.add("extends " + simpleTypes(type.getSuperclass().toString()));
            }
            if (!type.getInterfaces().isEmpty()) {
                names.add((type.getKind() == ElementKind.INTERFACE ? "extends " : "implements ")
                        + type.getInterfaces().stream().map(t -> simpleTypes(t.toString())).collect(Collectors.joining(", ")));
            }
            return names.isEmpty() ? "" : " " + String.join(" ", names);
        }

        private int line(Element e) {
            return SymbolResolver.line(cc, e);
        }

        private static String kind(Element e) {
            return switch (e.getKind()) {
                case ANNOTATION_TYPE -> "@interface";
                case ENUM_CONSTANT -> "enum constant";
                case RECORD_COMPONENT -> "record component";
                default -> e.getKind().name().toLowerCase(Locale.ROOT);
            };
        }

        String finish() {
            if (omitted > 0) {
                sb.append("\n... ").append(omitted).append(" more members not shown, raise 'limit' to see them\n");
            }
            return sb.toString();
        }
    }
}
