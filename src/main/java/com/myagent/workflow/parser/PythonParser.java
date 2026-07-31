package com.myagent.workflow.parser;

import com.myagent.workflow.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class PythonParser implements StructureParser {

    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "#\\s*@anchor:\\s*(\\w+)"  // Python 注释风格
    );

    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "^\\s*class\\s+(\\w+)\\s*(?:\\(([^)]*)\\))?\\s*:"
    );
    private static final Pattern DEF_PATTERN = Pattern.compile(
            "^\\s*def\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*:"
    );
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*(?:from\\s+(\\S+)\\s+)?import\\s+(.+)$"
    );
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^\\s*(\\w+)\\s*=\\s*(.+)$"  // 简单类变量赋值
    );

    @Override
    public boolean supports(Path file) {
        return file.getFileName().toString().toLowerCase().endsWith(".py");
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
        int currentClassIndent = -1; // 当前类的缩进空格数（类定义的缩进）
        boolean insideClass = false;

        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) continue;

            // 1. 提取锚点：去除字符串，保留注释
            String anchorLine = stripStringsOnly(rawLine);
            var matcher = ANCHOR_PATTERN.matcher(anchorLine);
            if (matcher.find()) {
                String id = matcher.group(1);
                // preview 保留原始行（含注释内容）
                anchors.add(new AnchorSummary(id, i + 1, rawLine.trim()));
            }

            // 2. 清理行：去除注释和字符串，得到纯净代码行
            String cleanLine = cleanLine(rawLine);
            String code = cleanLine.trim();

            // 3. 计算缩进空格数（基于原始行）
            int indent = rawLine.length() - rawLine.stripLeading().length();

            // 判断是否在类内部：如果当前行缩进大于类定义缩进，且当前类存在，则认为在类体内
            if (insideClass && currentClass != null && indent > currentClassIndent) {
                // 类体内
                // 检查是否为方法定义
                var defMatcher = DEF_PATTERN.matcher(trimmed);
                if (defMatcher.matches()) {
                    String methodName = defMatcher.group(1);
                    String params = defMatcher.group(2);
                    // 参数解析（简单拆分逗号）
                    List<String> paramList = parseParameters(params);
                    // 修饰符初步判断：根据方法名约定（但无法准确判断 static/classmethod，先省略）
                    String modifiers = ""; // 可尝试检测 @staticmethod 等装饰器，但需多行解析，简化
                    // 行号范围无法精确结束，先给起始行，结束行暂用同一行（后续可改进）
                    MethodDefinition method = new MethodDefinition(
                            methodName,
                            "?" + (methodName.equals("__init__") ? "" : "?"), // 返回类型未知
                            paramList,
                            modifiers,
                            i + 1,
                            i + 1, // 结束行暂时设置为开始行，因为无法准确推断方法结束位置
                            null
                    );
                    currentClass.addMethod(method);
                    continue;
                }

                // 检查是否为类变量（不是方法，且是赋值语句）
                var fieldMatcher = FIELD_PATTERN.matcher(trimmed);
                if (fieldMatcher.matches() && !trimmed.startsWith("def")) {
                    String varName = fieldMatcher.group(1);
                    String varValue = fieldMatcher.group(2);
                    FieldDefinition field = new FieldDefinition(
                            varName,
                            "?", // 类型未知
                            "",  // 无修饰符
                            i + 1
                    );
                    currentClass.addField(field);
                    continue;
                }

                // 其他行（如文档字符串、pass、或方法体内的赋值）忽略
                continue;
            }

            // 不在类体内（顶层）
            // 检查是否为导入
            var importMatcher = IMPORT_PATTERN.matcher(trimmed);
            if (importMatcher.matches()) {
                String from = importMatcher.group(1);
                String what = importMatcher.group(2);
                if (from != null && !from.isEmpty()) {
                    imports.add("from " + from + " import " + what);
                } else {
                    imports.add("import " + what);
                }
                continue;
            }

            // 检查是否为类定义
            var classMatcher = CLASS_PATTERN.matcher(trimmed);
            if (classMatcher.matches()) {
                String className = classMatcher.group(1);
                String parent = classMatcher.group(2);
                List<String> interfaces = new ArrayList<>();
                if (parent != null && !parent.isEmpty()) {
                    // 将父类作为接口列表的第一个（实际是继承，但简化）
                    interfaces.add(parent);
                }
                // 创建类定义，从当前行开始
                int startLine = i + 1;
                // 结束行未知，先给一个占位，后续可能通过缩进结束来更新
                currentClass = new ClassDefinitionBuilder(
                        className,
                        "class",
                        parent != null && !parent.isEmpty() ? parent : null,
                        interfaces,
                        startLine,
                        startLine
                );
                insideClass = true;
                currentClassIndent = indent;
                // 继续解析该行后的内容，不能直接跳出，因为可能后面紧跟方法定义（同一行？实际很少见）
                continue;
            }

            // 检查是否为顶层函数定义
            var defMatcher = DEF_PATTERN.matcher(trimmed);
            if (defMatcher.matches()) {
                String funcName = defMatcher.group(1);
                String params = defMatcher.group(2);
                List<String> paramList = parseParameters(params);
                MethodDefinition func = new MethodDefinition(
                        funcName,
                        "?", // 返回类型未知
                        paramList,
                        "",  // 无修饰符
                        i + 1,
                        i + 1,
                        null
                );
                topLevelFunctions.add(func);
                continue;
            }
        }

        // 如果当前有未完成的类，将其加入 classes 列表
        if (insideClass && currentClass != null) {
            // 尝试从后续行推算结束行（简单处理：如果下一个类定义出现，则结束行在此之前，但我们不修改）
            // 结束行目前已经设为 startLine，后续可以在检测到缩进减小时更新，但我们为了简化，先不优化。
            classes.add(currentClass.build());
            currentClass = null;
            insideClass = false;
        }

        return new FileStructure(
                file.toString(),
                "Python",
                null, // Python 无包名概念
                imports,
                classes,
                topLevelFunctions,
                topLevelFields,
                anchors
        );
    }

    /**
     * 解析参数字符串为列表（形如 "self, a, b=1" -> ["self", "a", "b=1"]）
     */
    private List<String> parseParameters(String params) {
        List<String> result = new ArrayList<>();
        if (params == null || params.trim().isEmpty()) return result;
        // 简单按逗号分割，不去除默认值细节
        String[] parts = params.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /**
     * 去除字符串（包括三引号）和字符常量，保留注释和代码。
     * Python 字符串语法：'...', "...", '''...''', """..."""
     */
    @Override
    public String stripStringsOnly(String rawLine) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean inTripleSingle = false;
        boolean inTripleDouble = false;
        boolean inSingle = false;
        boolean inDouble = false;
        char prev = 0;
        int i = 0;
        while (i < rawLine.length()) {
            char c = rawLine.charAt(i);
            // 检测三单引号
            if (!inString && !inTripleSingle && !inTripleDouble && !inSingle && !inDouble) {
                if (c == '\'' && i + 2 < rawLine.length() && rawLine.charAt(i + 1) == '\'' && rawLine.charAt(i + 2) == '\'') {
                    inTripleSingle = true;
                    i += 3;
                    continue;
                }
                if (c == '"' && i + 2 < rawLine.length() && rawLine.charAt(i + 1) == '"' && rawLine.charAt(i + 2) == '"') {
                    inTripleDouble = true;
                    i += 3;
                    continue;
                }
            }
            if (inTripleSingle) {
                if (c == '\'' && i + 2 < rawLine.length() && rawLine.charAt(i + 1) == '\'' && rawLine.charAt(i + 2) == '\'') {
                    inTripleSingle = false;
                    i += 3;
                    continue;
                }
                i++;
                continue;
            }
            if (inTripleDouble) {
                if (c == '"' && i + 2 < rawLine.length() && rawLine.charAt(i + 1) == '"' && rawLine.charAt(i + 2) == '"') {
                    inTripleDouble = false;
                    i += 3;
                    continue;
                }
                i++;
                continue;
            }
            if (!inString && !inSingle && !inDouble) {
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
        boolean inComment = false;
        for (int i = 0; i < noStrings.length(); i++) {
            char c = noStrings.charAt(i);
            if (inComment) {
                // 注释一直持续到行尾
                break;
            }
            if (c == '#' && (i == 0 || noStrings.charAt(i - 1) != '\\')) {
                inComment = true;
                break;
            }
            result.append(c);
        }
        return result.toString();
    }

    /**
     * 辅助类：构建 ClassDefinition（复用 JavaParser 中的同名内部类）
     */
    private static class ClassDefinitionBuilder {
        private final String name;
        private final String type;
        private final String superClass;
        private final List<String> interfaces;
        private final int startLine;
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