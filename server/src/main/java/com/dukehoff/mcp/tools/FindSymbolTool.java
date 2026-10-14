/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.SourceUtils;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Tool;
import com.dukehoff.mcp.Workspace;
import org.openide.filesystems.FileObject;

/**
 * Finds types declared in the workspace sources, or members of them, by (partial) name.
 */
public final class FindSymbolTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_TYPES_FOR_MEMBERS = 20;

    private final Workspace workspace;

    public FindSymbolTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "find_symbol";
    }

    @Override
    public String description() {
        return "Find classes, interfaces, enums and records declared in the workspace sources by name, "
                + "or members of them. Returns canonical symbols (to use with the other tools), kind and "
                + "location. The query is a case-insensitive prefix of the simple type name (\"OrderServ\"), "
                + "a glob (\"*Service\", \"Order*Impl\") or a partially qualified name (\"billing.Invoice\"). "
                + "Add '#member' to search members of the matching types: \"OrderService#save\" (prefix), "
                + "\"*Repository#find*\" (glob), \"Order#<init>\" (constructors). With include_libraries also the "
                + "JDK and the project's dependencies (their public API): look up a library method's exact name "
                + "and overloads instead of guessing, e.g. \"commons.lang3.StringUtils#abbrev\".";
    }

    @Override
    public List<String> searchTerms() {
        return List.of("find class", "find type", "search symbol", "go to type", "qualified name");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "query", Json.obj("type", "string",
                                "description", "Type name, prefix or glob, optionally followed by #member"),
                        "include_libraries", Json.obj("type", "boolean",
                                "description", "Also search the JDK and the dependencies on the class path, default false"),
                        "limit", Json.obj("type", "integer", "description", "Maximum results, default " + DEFAULT_LIMIT)),
                "required", List.of("query"));
    }

    /** A type found in the index: in the workspace sources (file), or a library type (library). */
    private record TypeHit(String kind, FileObject file, LibraryTypes.Found library) {

        String location(Workspace workspace) {
            return library != null ? "[library: " + library.origin() + "]" : file != null ? workspace.displayPath(file) : "?";
        }
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String query = Json.string(arguments, "query");
        if (query == null) {
            return Result.error("'query' is required");
        }
        int limit = Json.integer(arguments, "limit", DEFAULT_LIMIT);
        List<FileObject> roots;
        try {
            roots = workspace.awaitSourceRoots();
        } catch (Workspace.NotReadyException ex) {
            return Result.error(ex.getMessage());
        }
        int hash = query.indexOf('#');
        String typeQuery = hash < 0 ? query : query.substring(0, hash);
        String memberQuery = hash < 0 ? null : query.substring(hash + 1);
        if (typeQuery.isEmpty() || memberQuery != null && memberQuery.isEmpty()) {
            return Result.error("Use 'Type' or 'Type#member', e.g. OrderService#save");
        }

        boolean libraries = Boolean.TRUE.equals(arguments.get("include_libraries"));
        Map<String, TypeHit> types = findTypes(typeQuery, roots, libraries);
        if (types.isEmpty()) {
            return Result.ok("No types matching '" + typeQuery + "' in the workspace sources"
                    + (libraries ? " or on their class paths." : " (add include_libraries: true to search the JDK and the dependencies)."));
        }
        // canonical symbol -> "kind  location"
        Map<String, String> found = new TreeMap<>();
        String note = "";
        if (memberQuery == null) {
            types.forEach((fqn, hit) -> found.put(fqn, hit.kind() + "  " + hit.location(workspace)));
        } else {
            if (types.size() > MAX_TYPES_FOR_MEMBERS) {
                note = "(members of the first " + MAX_TYPES_FOR_MEMBERS + " of " + types.size()
                        + " matching types; qualify the type part to narrow)\n";
            }
            findMembers(types, memberQuery, found);
            if (found.isEmpty()) {
                return Result.ok(note + "No members matching '" + memberQuery + "' in " + types.size()
                        + (types.size() == 1 ? " type" : " types") + " matching '" + typeQuery + "'.");
            }
        }
        StringBuilder sb = new StringBuilder(note);
        int shown = 0;
        for (Map.Entry<String, String> e : found.entrySet()) {
            if (shown++ == limit) {
                sb.append("... ").append(found.size() - limit).append(" more, refine the query or raise 'limit'\n");
                break;
            }
            sb.append(e.getKey()).append("  ").append(e.getValue()).append('\n');
        }
        return Result.ok(sb.toString());
    }

    private Map<String, TypeHit> findTypes(String query, List<FileObject> roots, boolean libraries) {
        String simple = query.substring(query.lastIndexOf('.') + 1);
        String qualifierFilter = query.contains(".") ? query.substring(0, query.lastIndexOf('.')).toLowerCase(Locale.ROOT) : null;
        boolean glob = simple.contains("*") || simple.contains("?");
        ClassIndex.NameKind kind = glob ? ClassIndex.NameKind.CASE_INSENSITIVE_REGEXP : ClassIndex.NameKind.CASE_INSENSITIVE_PREFIX;
        String pattern = glob ? globToRegex(simple) : simple;

        Map<String, TypeHit> found = new TreeMap<>();
        if (libraries) {
            LibraryTypes.find(pattern, kind, roots,
                    fqn -> qualifierFilter == null || fqn.toLowerCase(Locale.ROOT).contains(qualifierFilter))
                    .forEach((fqn, lib) -> found.put(fqn, new TypeHit(lib.kind(), null, lib)));
        }
        for (FileObject root : roots) {
            ClasspathInfo cpInfo = ClasspathInfo.create(root);
            Set<ElementHandle<TypeElement>> types = cpInfo.getClassIndex()
                    .getDeclaredTypes(pattern, kind, EnumSet.of(ClassIndex.SearchScope.SOURCE));
            if (types == null) {
                continue;
            }
            for (ElementHandle<TypeElement> h : types) {
                String fqn = h.getQualifiedName().replace('$', '.');
                if (qualifierFilter != null && !fqn.toLowerCase(Locale.ROOT).contains(qualifierFilter)) {
                    continue;
                }
                found.computeIfAbsent(fqn, k -> new TypeHit(h.getKind().name().toLowerCase(Locale.ROOT),
                        SourceUtils.getFile(h, cpInfo), null));
            }
        }
        return found;
    }

    private void findMembers(Map<String, TypeHit> types, String memberQuery, Map<String, String> into) throws IOException {
        boolean constructors = SymbolSpec.CONSTRUCTOR.equals(memberQuery);
        Pattern pattern = memberQuery.contains("*") || memberQuery.contains("?")
                ? Pattern.compile(globToRegex(memberQuery), Pattern.CASE_INSENSITIVE)
                : Pattern.compile(Pattern.quote(memberQuery) + ".*", Pattern.CASE_INSENSITIVE);
        // parse each file once for all of its matching types
        Map<FileObject, List<String>> byFile = new LinkedHashMap<>();
        List<LibraryTypes.Found> library = new ArrayList<>();
        types.entrySet().stream().limit(MAX_TYPES_FOR_MEMBERS).forEach(e -> {
            if (e.getValue().library() != null) {
                library.add(e.getValue().library());
            } else if (e.getValue().file() != null) {
                byFile.computeIfAbsent(e.getValue().file(), f -> new ArrayList<>()).add(e.getKey());
            }
        });
        for (LibraryTypes.Found lib : library) {
            // a library type's API only, read from its class file
            JavaSource.create(lib.cpInfo()).runUserActionTask(cc -> {
                TypeElement type = lib.handle().resolve(cc);
                if (type == null) {
                    return;
                }
                for (Element e : type.getEnclosedElements()) {
                    boolean api = e.getModifiers().contains(Modifier.PUBLIC) || e.getModifiers().contains(Modifier.PROTECTED);
                    if (api && memberMatches(e, constructors, pattern)) {
                        into.put(SymbolResolver.signature(e, cc), e.getKind().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                                + "  [library: " + lib.origin() + "]" + (cc.getElements().isDeprecated(e) ? "  (deprecated)" : ""));
                    }
                }
            }, true);
        }
        for (Map.Entry<FileObject, List<String>> f : byFile.entrySet()) {
            JavaSource js = JavaSource.forFileObject(f.getKey());
            if (js == null) {
                continue;
            }
            String where = workspace.displayPath(f.getKey());
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                for (String fqn : f.getValue()) {
                    TypeElement type = cc.getElements().getTypeElement(fqn);
                    if (type == null) {
                        continue;
                    }
                    for (Element e : type.getEnclosedElements()) {
                        if (memberMatches(e, constructors, pattern)) {
                            boolean implicit = cc.getElements().getOrigin(e) != Elements.Origin.EXPLICIT;
                            int line = implicit ? 0 : SymbolResolver.line(cc, e);
                            into.put(SymbolResolver.signature(e, cc), e.getKind().name().toLowerCase(Locale.ROOT)
                                    .replace('_', ' ') + "  " + where + (line > 0 ? ":" + line : "")
                                    + (implicit ? "  (implicit)" : ""));
                        }
                    }
                }
            }, true);
        }
    }

    private static boolean memberMatches(Element e, boolean constructors, Pattern pattern) {
        return switch (e.getKind()) {
            case CONSTRUCTOR -> constructors;
            case METHOD, FIELD, ENUM_CONSTANT, RECORD_COMPONENT -> !constructors && pattern.matcher(e.getSimpleName()).matches();
            default -> false;
        };
    }

    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if (Character.isJavaIdentifierPart(c)) {
                        sb.append(c);
                    } else {
                        sb.append('\\').append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
