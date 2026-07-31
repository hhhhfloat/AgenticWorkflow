package com.myagent.workflow.parser;

import com.myagent.workflow.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class CppParser implements StructureParser {

    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "//\\s*@anchor:\\s*(\\w+)|" +
                    "/\\*\\s*@anchor:\\s*(\\w+)\\s*\\*/"
    );

    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
            "^\\s*#include\\s*[<\"]([^>\"]+)[>\"]"
    );

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile(
            "^\\s*namespace\\s+(\\w+)\\s*\\{?"
    );

    // 类/结构体/联合体定义
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "^\\s*(?:template\\s*<[^>]*>\\s*)?(class|struct|union)\\s+(\\w+)\\s*(?::\\s*([^{]+))?\\s*\\{?"
    );

    // 前置声明
    private static final Pattern FORWARD_DECL_PATTERN = Pattern.compile(
            "^\\s*(?:template\\s*<[^>]*>\\s*)?(class|struct|union)\\s+(\\w+)\\s*;"
    );

    // 方法定义（支持 const, override, final, = default, = delete）
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^(?:(?:virtual|static|inline|constexpr|explicit)\\s+)*(\\w+(?:<[^>]*>)?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:const\\s*)?(?:override\\s*)?(?:final\\s*)?(?:\\s*=\\s*(?:default|delete)\\s*)?(?:\\{?|;?)"
    );

    // 方法定义（析构函数 ~）
    private static final Pattern DESTRUCTOR_PATTERN = Pattern.compile(
            "^(?:(?:virtual|static|inline|constexpr)\\s+)*(~\\w+)\\s*\\(([^)]*)\\)\\s*(?:\\{?|;?)"
    );

    // 字段定义（支持指针、引用、数组）
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^(?:static\\s+|const\\s+|mutable\\s+)*(\\w+(?:<[^>]*>)?)\\s+(\\w+)\\s*(?:\\[\\d*\\])?\\s*(?:;|=)"
    );

    // 访问修饰符
    private static final Pattern ACCESS_PATTERN = Pattern.compile(
            "^\\s*(public|private|protected)\\s*:"
    );

    // 花括号检测
    private static final Pattern OPEN_BRACE = Pattern.compile("\\{");
    private static final Pattern CLOSE_BRACE = Pattern.compile("\\}");

    // 结束句点
    private static final Pattern SEMICOLON = Pattern.compile(";");

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".cpp") || name.endsWith(".cc") ||
                name.endsWith(".cxx") || name.endsWith(".h") ||
                name.endsWith(".hpp") || name.endsWith(".hxx");
    }

    @Override
    public FileStructure parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        List<AnchorSummary> anchors = new ArrayList<>();
        List<String> includes = new ArrayList<>();
        List<ClassDefinition> classes = new ArrayList<>();
        List<MethodDefinition> topLevelFunctions = new ArrayList<>();
        List<FieldDefinition> topLevelFields = new ArrayList<>();

        // 命名空间栈
        List<String> namespaceStack = new ArrayList<>();
        int namespaceDepth = -1;
        boolean inNamespace = false;

        // 类上下文
        ClassDefinitionBuilder currentClass = null;
        int classStartDepth = -1;      // 类定义开始时的花括号深度
        String currentAccess = "private";
        boolean insideClass = false;
        int classStartLine = -1;

        // 方法上下文（用于跟踪方法结束）
        MethodDefinition pendingMethod = null;
        int methodStartDepth = -1;
        int methodStartLine = -1;

        // 全局花括号深度
        int braceDepth = 0;

        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String trimmed = rawLine.trim();

            // 1. 锚点检测：先用 stripStringsOnly 去除字符串，再匹配锚点
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
                    anchors.add(new AnchorSummary(id, i + 1, trimmed));
                }
            }

            // 2. 清理行：去除注释和字符串
            String cleanLine = cleanLine(rawLine);
            if (cleanLine.trim().isEmpty()) continue;
            trimmed = cleanLine.trim();

            // 更新花括号深度（处理多行如 "class X {" 中的 {）
            if (OPEN_BRACE.matcher(trimmed).find()) {
                braceDepth++;
            }
            if (CLOSE_BRACE.matcher(trimmed).find()) {
                braceDepth--;
            }

            // 检测命名空间
            var nsMatcher = NAMESPACE_PATTERN.matcher(trimmed);
            if (nsMatcher.matches()) {
                String ns = nsMatcher.group(1);
                namespaceStack.add(ns);
                inNamespace = true;
                // 记录命名空间开始深度（如果未记录）
                if (namespaceDepth == -1) {
                    namespaceDepth = braceDepth;
                }
                continue;
            }

            // 检测命名空间结束（如果 braceDepth < namespaceDepth）
            if (inNamespace && namespaceDepth >= 0 && braceDepth < namespaceDepth) {
                if (!namespaceStack.isEmpty()) {
                    namespaceStack.remove(namespaceStack.size() - 1);
                }
                namespaceDepth = -1;
                inNamespace = false;
            }

            // 检测 #include
            var includeMatcher = INCLUDE_PATTERN.matcher(trimmed);
            if (includeMatcher.matches()) {
                includes.add("#include " + includeMatcher.group(1));
                continue;
            }

            // 检测前置声明（忽略）
            var forwardMatcher = FORWARD_DECL_PATTERN.matcher(trimmed);
            if (forwardMatcher.matches()) {
                continue;
            }

            // 检测类/结构体定义
            var classMatcher = CLASS_PATTERN.matcher(trimmed);
            if (classMatcher.matches()) {
                String type = classMatcher.group(1);
                String name = classMatcher.group(2);
                String inheritance = classMatcher.group(3);

                String superClass = null;
                List<String> interfaces = new ArrayList<>();
                if (inheritance != null && !inheritance.isEmpty()) {
                    String[] parts = inheritance.split(",");
                    for (String part : parts) {
                        String cleaned = part.trim().replaceAll("\\b(public|private|protected)\\s+", "");
                        if (superClass == null) {
                            superClass = cleaned;
                        } else {
                            interfaces.add(cleaned);
                        }
                    }
                }

                // 获取命名空间前缀
                String nsPrefix = !namespaceStack.isEmpty() ? String.join("::", namespaceStack) + "::" : "";
                String fullName = nsPrefix + name;

                currentClass = new ClassDefinitionBuilder(
                        fullName,
                        type,
                        superClass,
                        interfaces,
                        i + 1,
                        i + 1
                );
                insideClass = true;
                classStartDepth = braceDepth;
                classStartLine = i + 1;
                currentAccess = "private";
                continue;
            }

            // 如果在类内部
            if (insideClass && currentClass != null) {
                // 检测访问修饰符
                var accessMatcher = ACCESS_PATTERN.matcher(trimmed);
                if (accessMatcher.matches()) {
                    currentAccess = accessMatcher.group(1);
                    continue;
                }

                // 检测类结束：braceDepth 回到 classStartDepth - 1
                if (braceDepth < classStartDepth) {
                    // 类结束
                    currentClass.endLine = i + 1;
                    classes.add(currentClass.build());
                    currentClass = null;
                    insideClass = false;
                    pendingMethod = null;
                    continue;
                }

                // 检测是否为方法定义
                boolean isMethod = false;

                // 尝试析构函数
                var destMatcher = DESTRUCTOR_PATTERN.matcher(trimmed);
                if (destMatcher.matches()) {
                    String methodName = destMatcher.group(1);
                    String params = destMatcher.group(2);
                    List<String> paramList = parseParameters(params);
                    String returnType = "~" + methodName;
                    MethodDefinition method = new MethodDefinition(
                            methodName,
                            returnType,
                            paramList,
                            currentAccess,
                            i + 1,
                            i + 1, // 结束行稍后更新
                            null
                    );
                    currentClass.addMethod(method);
                    isMethod = true;
                    // 检查是否为内联实现（含 { 或 ;）
                    if (trimmed.contains("{") || trimmed.contains(";")) {
                        // 方法在一行结束，结束行就是当前行
                    }
                }

                // 尝试普通方法
                var methodMatcher = METHOD_PATTERN.matcher(trimmed);
                if (!isMethod && methodMatcher.matches()) {
                    String returnType = methodMatcher.group(1);
                    String methodName = methodMatcher.group(2);
                    String params = methodMatcher.group(3);
                    List<String> paramList = parseParameters(params);
                    MethodDefinition method = new MethodDefinition(
                            methodName,
                            returnType,
                            paramList,
                            currentAccess,
                            i + 1,
                            i + 1,
                            null
                    );
                    currentClass.addMethod(method);
                    isMethod = true;
                }

                // 检测是否为字段
                if (!isMethod) {
                    var fieldMatcher = FIELD_PATTERN.matcher(trimmed);
                    if (fieldMatcher.matches()) {
                        String type = fieldMatcher.group(1);
                        String name = fieldMatcher.group(2);
                        FieldDefinition field = new FieldDefinition(
                                name,
                                type,
                                currentAccess,
                                i + 1
                        );
                        currentClass.addField(field);
                        continue;
                    }
                }

                // 其他行忽略
                continue;
            }

            // 不在类内：检测顶层函数（自由函数，仅限 .cpp）
            if (!trimmed.startsWith("#") && !trimmed.startsWith("namespace") && !trimmed.startsWith("class") && !trimmed.startsWith("struct")) {
                var methodMatcher = METHOD_PATTERN.matcher(trimmed);
                if (methodMatcher.matches()) {
                    String returnType = methodMatcher.group(1);
                    String funcName = methodMatcher.group(2);
                    String params = methodMatcher.group(3);
                    List<String> paramList = parseParameters(params);
                    MethodDefinition func = new MethodDefinition(
                            funcName,
                            returnType,
                            paramList,
                            "free",
                            i + 1,
                            i + 1,
                            null
                    );
                    topLevelFunctions.add(func);
                }
            }
        }

        // 处理未闭合的类
        if (insideClass && currentClass != null) {
            currentClass.endLine = lines.size();
            classes.add(currentClass.build());
        }

        // 包名：用命名空间组合
        String packageName = !namespaceStack.isEmpty() ? String.join("::", namespaceStack) : null;

        return new FileStructure(
                file.toString(),
                "C++",
                packageName,
                includes,
                classes,
                topLevelFunctions,
                topLevelFields,
                anchors
        );
    }

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

    /**
     * 去除字符串和字符常量，保留注释和代码
     */
    @Override
    public String stripStringsOnly(String rawLine) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean inChar = false;
        char prev = 0;
        for (int i = 0; i < rawLine.length(); i++) {
            char c = rawLine.charAt(i);
            if (inString) {
                if (c == '"' && prev != '\\') {
                    inString = false;
                }
                prev = c;
                continue;
            }
            if (inChar) {
                if (c == '\'' && prev != '\\') {
                    inChar = false;
                }
                prev = c;
                continue;
            }
            if (c == '"') {
                inString = true;
                prev = c;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                prev = c;
                continue;
            }
            result.append(c);
            prev = c;
        }
        return result.toString();
    }

    @Override
    public String cleanLine(String rawLine) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char prev = 0;
        for (int i = 0; i < rawLine.length(); i++) {
            char c = rawLine.charAt(i);
            if (inLineComment) {
                break; // 忽略行注释剩余部分
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < rawLine.length() && rawLine.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++; // 跳过 '/'
                }
                continue;
            }
            if (inString) {
                if (c == '"' && prev != '\\') {
                    inString = false;
                }
                prev = c;
                continue;
            }
            if (inChar) {
                if (c == '\'' && prev != '\\') {
                    inChar = false;
                }
                prev = c;
                continue;
            }
            // 多行注释起始
            if (c == '/' && i + 1 < rawLine.length()) {
                char next = rawLine.charAt(i + 1);
                if (next == '*') {
                    inBlockComment = true;
                    i++; // 跳过 '*'
                    continue;
                } else if (next == '/') {
                    inLineComment = true;
                    break; // 忽略剩余部分
                }
            }
            // 字符串起始
            if (c == '"') {
                inString = true;
                prev = c;
                continue;
            }
            // 字符常量起始
            if (c == '\'') {
                inChar = true;
                prev = c;
                continue;
            }
            // 保留非注释非字符串字符
            result.append(c);
            prev = c;
        }
        return result.toString();
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