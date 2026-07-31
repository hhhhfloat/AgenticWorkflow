package com.myagent.workflow.parser;

import com.myagent.workflow.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class JavaScriptParser implements StructureParser {

    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "//\\s*@anchor:\\s*(\\w+)|" +
                    "/\\*\\s*@anchor:\\s*(\\w+)\\s*\\*/"
    );

    // import 语句: import ... from '...' 或 require(...)
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+(?:(?:\\{[^}]*\\}|\\*\\s+as\\s+\\w+|\\w+)\\s+from\\s+)?['\"]([^'\"]+)['\"]|" +
                    "^\\s*(?:const|let|var)\\s+\\w+\\s*=\\s*require\\s*\\(['\"]([^'\"]+)['\"]\\)"
    );

    // 类定义
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:abstract\\s+)?class\\s+(\\w+)\\s*(?:extends\\s+(\\w+))?\\s*(?:implements\\s+[^{]+)?\\s*\\{?"
    );

    // 方法定义（类内）
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^\\s*(?:async\\s+)?(?:static\\s+)?(?:get\\s+|set\\s+)?(\\w+)\\s*\\(([^)]*)\\)\\s*(?::\\s*\\w+)?\\s*\\{?"
    );

    // 构造函数
    private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile(
            "^\\s*constructor\\s*\\(([^)]*)\\)\\s*\\{?"
    );

    // 顶层函数声明
    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{?"
    );

    // 类字段（简单赋值）
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^\\s*(?:static\\s+)?(?:readonly\\s+)?(\\w+)\\s*(?:=\\s*[^;]+)?\\s*;?"
    );

    // 花括号检测
    private static final Pattern OPEN_BRACE = Pattern.compile("\\{");
    private static final Pattern CLOSE_BRACE = Pattern.compile("\\}");

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".js") || name.endsWith(".jsx") ||
                name.endsWith(".ts") || name.endsWith(".tsx") ||
                name.endsWith(".mjs") || name.endsWith(".cjs");
    }

    @Override
    public FileStructure parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        List<AnchorSummary> anchors = new ArrayList<>();
        List<String> imports = new ArrayList<>();
        List<ClassDefinition> classes = new ArrayList<>();
        List<MethodDefinition> topLevelFunctions = new ArrayList<>();
        List<FieldDefinition> topLevelFields = new ArrayList<>();

        ClassDefinitionBuilder currentClass = null;
        int classStartDepth = -1;
        boolean insideClass = false;
        int braceDepth = 0;

        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String trimmed = rawLine.trim();

            // 1. 提取锚点（基于去除字符串的行）
            String anchorLine = stripStringsOnly(rawLine);
            var anchorMatcher = ANCHOR_PATTERN.matcher(anchorLine);
            if (anchorMatcher.find()) {
                String id = null;
                for (int j = 1; j <= anchorMatcher.groupCount(); j++) {
                    String candidate = anchorMatcher.group(j);
                    if (candidate != null) {
                        id = candidate;
                        break;
                    }
                }
                if (id != null) {
                    anchors.add(new AnchorSummary(id, i + 1, rawLine.trim()));
                }
            }

            // 2. 清理行（去除注释和字符串）
            String cleanLine = cleanLine(rawLine);
            String code = cleanLine.trim();
            if (code.isEmpty()) {
                // 没有有效代码，但可能影响花括号深度（空行无花括号）
                // 仍更新花括号深度（基于code，无变化）
                continue;
            }

            // 3. 更新花括号深度（基于清理后的行）
            if (OPEN_BRACE.matcher(code).find()) {
                braceDepth++;
            }
            if (CLOSE_BRACE.matcher(code).find()) {
                braceDepth--;
            }

            // 4. 检测类结束
            if (insideClass && currentClass != null && braceDepth < classStartDepth) {
                currentClass.endLine = i + 1;
                classes.add(currentClass.build());
                currentClass = null;
                insideClass = false;
                continue;
            }

            // 5. 检测导入
            var importMatcher = IMPORT_PATTERN.matcher(code);
            if (importMatcher.matches()) {
                String module = importMatcher.group(1);
                if (module != null) {
                    imports.add("import " + module);
                }
                continue;
            }

            // 6. 检测类定义
            var classMatcher = CLASS_PATTERN.matcher(code);
            if (classMatcher.matches()) {
                String className = classMatcher.group(1);
                String parent = classMatcher.group(2);
                currentClass = new ClassDefinitionBuilder(
                        className,
                        "class",
                        parent,
                        new ArrayList<>(),
                        i + 1,
                        i + 1
                );
                insideClass = true;
                classStartDepth = braceDepth;
                continue;
            }

            // 7. 如果在类内部
            if (insideClass && currentClass != null) {
                // 检测构造函数
                var constrMatcher = CONSTRUCTOR_PATTERN.matcher(code);
                if (constrMatcher.matches()) {
                    String params = constrMatcher.group(1);
                    List<String> paramList = parseParameters(params);
                    MethodDefinition method = new MethodDefinition(
                            "constructor",
                            "void",
                            paramList,
                            "",
                            i + 1,
                            i + 1,
                            null
                    );
                    currentClass.addMethod(method);
                    continue;
                }

                // 检测方法
                var methodMatcher = METHOD_PATTERN.matcher(code);
                if (methodMatcher.matches()) {
                    String methodName = methodMatcher.group(1);
                    String params = methodMatcher.group(2);
                    // 排除类名同名的方法（可能是构造函数，但已处理）
                    if (!methodName.equals(currentClass.name)) {
                        List<String> paramList = parseParameters(params);
                        MethodDefinition method = new MethodDefinition(
                                methodName,
                                "?", // 返回类型未知
                                paramList,
                                "",
                                i + 1,
                                i + 1,
                                null
                        );
                        currentClass.addMethod(method);
                        continue;
                    }
                }

                // 检测字段（简单赋值）
                var fieldMatcher = FIELD_PATTERN.matcher(code);
                if (fieldMatcher.matches()) {
                    String fieldName = fieldMatcher.group(1);
                    FieldDefinition field = new FieldDefinition(
                            fieldName,
                            "?",
                            "",
                            i + 1
                    );
                    currentClass.addField(field);
                    continue;
                }

                // 其他行（如方法体、表达式等）忽略
                continue;
            }

            // 8. 不在类内：检测顶层函数
            var funcMatcher = FUNCTION_PATTERN.matcher(code);
            if (funcMatcher.matches()) {
                String funcName = funcMatcher.group(1);
                String params = funcMatcher.group(2);
                List<String> paramList = parseParameters(params);
                MethodDefinition func = new MethodDefinition(
                        funcName,
                        "?",
                        paramList,
                        "",
                        i + 1,
                        i + 1,
                        null
                );
                topLevelFunctions.add(func);
                continue;
            }

            // 可能还有变量声明、表达式等，忽略
        }

        // 处理未闭合的类
        if (insideClass && currentClass != null) {
            currentClass.endLine = lines.size();
            classes.add(currentClass.build());
        }

        return new FileStructure(
                file.toString(),
                "JavaScript",
                null, // JavaScript 无包名
                imports,
                classes,
                topLevelFunctions,
                topLevelFields,
                anchors
        );
    }

    // ==================== 字符串/注释清理 ====================
    @Override
    public String stripStringsOnly(String rawLine) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean inTemplate = false;
        boolean inSingle = false;
        boolean inDouble = false;
        char prev = 0;
        for (int i = 0; i < rawLine.length(); i++) {
            char c = rawLine.charAt(i);
            if (!inString && !inSingle && !inDouble && !inTemplate) {
                // 检测模板字符串
                if (c == '`') {
                    inTemplate = true;
                    prev = c;
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inSingle = true;
                    prev = c;
                    i++;
                    continue;
                }
                if (c == '"') {
                    inDouble = true;
                    prev = c;
                    i++;
                    continue;
                }
            }
            if (inSingle) {
                if (c == '\'' && prev != '\\') {
                    inSingle = false;
                }
                prev = c;
                i++;
                continue;
            }
            if (inDouble) {
                if (c == '"' && prev != '\\') {
                    inDouble = false;
                }
                prev = c;
                i++;
                continue;
            }
            if (inTemplate) {
                if (c == '`' && prev != '\\') {
                    inTemplate = false;
                }
                prev = c;
                i++;
                continue;
            }
            // 普通字符
            result.append(c);
            prev = c;
            i++;
        }
        return result.toString();
    }

    @Override
    public String cleanLine(String rawLine) {
        String noStrings = stripStringsOnly(rawLine);
        StringBuilder result = new StringBuilder();
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < noStrings.length(); i++) {
            char c = noStrings.charAt(i);
            if (inLineComment) {
                break;
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < noStrings.length() && noStrings.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < noStrings.length()) {
                char next = noStrings.charAt(i + 1);
                if (next == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                } else if (next == '/') {
                    inLineComment = true;
                    break;
                }
            }
            result.append(c);
        }
        return result.toString();
    }

    // ==================== 辅助方法 ====================

    private List<String> parseParameters(String params) {
        List<String> result = new ArrayList<>();
        if (params == null || params.trim().isEmpty()) return result;
        String[] parts = params.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private static class ClassDefinitionBuilder {
        private final String name;
        private final String type;
        private final String superClass;
        private final List<String> interfaces;
        private int startLine;
        private int endLine;
        private final List<MethodDefinition> methods = new ArrayList<>();
        private final List<FieldDefinition> fields = new ArrayList<>();
        private final List<AnchorSummary> anchors = new ArrayList<>();

        ClassDefinitionBuilder(String name, String type, String superClass, List<String> interfaces,
                               int startLine, int endLine) {
            this.name = name;
            this.type = type;
            this.superClass = superClass;
            this.interfaces = interfaces;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        void addMethod(MethodDefinition m) { methods.add(m); }
        void addField(FieldDefinition f) { fields.add(f); }

        ClassDefinition build() {
            return new ClassDefinition(
                    name, type, superClass, interfaces,
                    methods, fields, anchors, startLine, endLine
            );
        }
    }
}