package com.myagent.workflow.parser;

import com.myagent.workflow.model.*;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.tools.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class JavaParser implements StructureParser {

    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "//\\s*@anchor:\\s*(\\w+)|" +
                    "/\\*\\s*@anchor:\\s*(\\w+)\\s*\\*/"
    );

    @Override
    public boolean supports(Path file) {
        return file.getFileName().toString().toLowerCase().endsWith(".java");
    }

    @Override
    public FileStructure parse(Path file) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return parseWithRegex(file);
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> fileObjects = fileManager.getJavaFileObjects(file.toFile());

            // 添加编译选项
            List<String> options = Arrays.asList(
                    "-source", "21",
                    "-cp", System.getProperty("java.class.path")
            );

            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null, options, null, fileObjects);

            Iterable<? extends CompilationUnitTree> trees = task.parse();
            CompilationUnitTree cu = trees.iterator().next();

            // 获取 Trees 工具实例
            Trees treesUtil = Trees.instance(task);
            SourcePositions srcPos = treesUtil.getSourcePositions();

            String packageName = cu.getPackageName() != null ? cu.getPackageName().toString() : null;
            List<String> imports = new ArrayList<>();
            for (ImportTree imp : cu.getImports()) {
                imports.add(imp.getQualifiedIdentifier().toString());
            }

            StructureCollector collector = new StructureCollector(file.toString(), cu, srcPos);
            cu.accept(collector, null);

            List<ClassDefinition> classes = collector.classes;
            List<MethodDefinition> functions = collector.topLevelFunctions;
            List<FieldDefinition> fields = collector.topLevelFields;
            List<AnchorSummary> anchors = extractAnchors(file);

            // 如果 AST 没有解析出任何类，可能是解析失败，回退到正则解析
            if (classes.isEmpty() && anchors.isEmpty()) {
                // 如果连锚点都没有，说明文件可能为空或不是 Java 文件，直接用正则
                return parseWithRegex(file);
            } else if (classes.isEmpty() && !anchors.isEmpty()) {
                // 有锚点但无类结构，可能 AST 解析不完整，回退到正则
                return parseWithRegex(file);
            }

            return new FileStructure(
                    file.toString(),
                    "Java",
                    packageName,
                    imports,
                    classes,
                    functions,
                    fields,
                    anchors
            );
        } catch (Exception e) {
            // 打印异常堆栈，便于调试
            e.printStackTrace();
            return parseWithRegex(file);
        }
    }

    private static class StructureCollector extends TreeScanner<Void, Void> {
        private final String filePath;
        private final CompilationUnitTree cu;
        private final SourcePositions srcPos;
        private final List<ClassDefinition> classes = new ArrayList<>();
        private final List<MethodDefinition> topLevelFunctions = new ArrayList<>();
        private final List<FieldDefinition> topLevelFields = new ArrayList<>();

        private ClassDefinitionBuilder currentClassBuilder = null;

        StructureCollector(String filePath, CompilationUnitTree cu, SourcePositions srcPos) {
            this.filePath = filePath;
            this.cu = cu;
            this.srcPos = srcPos;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            String name = node.getSimpleName().toString();
            String type = "class"; // 简化，可后续增强
            String superClass = node.getExtendsClause() != null ? node.getExtendsClause().toString() : null;
            List<String> interfaces = new ArrayList<>();
            if (node.getImplementsClause() != null) {
                for (Tree t : node.getImplementsClause()) {
                    interfaces.add(t.toString());
                }
            }

            long startPos = srcPos.getStartPosition(cu, node);
            long endPos = srcPos.getEndPosition(cu, node);
            int startLine = getLineNumber(startPos);
            int endLine = getLineNumber(endPos);

            currentClassBuilder = new ClassDefinitionBuilder(name, type, superClass, interfaces, startLine, endLine);

            for (Tree member : node.getMembers()) {
                member.accept(this, null);
            }

            classes.add(currentClassBuilder.build());
            currentClassBuilder = null;
            return null;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            String name = node.getName().toString();
            String returnType = node.getReturnType() != null ? node.getReturnType().toString() : "void";
            List<String> parameters = new ArrayList<>();
            if (node.getParameters() != null) {
                for (VariableTree param : node.getParameters()) {
                    parameters.add(param.getType() + " " + param.getName());
                }
            }
            String modifiers = node.getModifiers() != null ? node.getModifiers().getFlags().toString() : "";

            long startPos = srcPos.getStartPosition(cu, node);
            long endPos = srcPos.getEndPosition(cu, node);
            int startLine = getLineNumber(startPos);
            int endLine = getLineNumber(endPos);

            MethodDefinition method = new MethodDefinition(
                    name, returnType, parameters, modifiers, startLine, endLine, null
            );

            if (currentClassBuilder != null) {
                currentClassBuilder.addMethod(method);
            } else {
                topLevelFunctions.add(method);
            }
            return null;
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            String name = node.getName().toString();
            String type = node.getType() != null ? node.getType().toString() : "";
            String modifiers = node.getModifiers() != null ? node.getModifiers().getFlags().toString() : "";

            long pos = srcPos.getStartPosition(cu, node);
            int line = getLineNumber(pos);

            FieldDefinition field = new FieldDefinition(name, type, modifiers, line);

            if (currentClassBuilder != null) {
                currentClassBuilder.addField(field);
            } else {
                topLevelFields.add(field);
            }
            return null;
        }

        private int getLineNumber(long position) {
            if (position < 0) return -1;
            try {
                String content = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
                if (position >= content.length()) {
                    return (int) content.chars().filter(ch -> ch == '\n').count() + 1;
                }
                String prefix = content.substring(0, (int) position);
                return (int) prefix.chars().filter(ch -> ch == '\n').count() + 1;
            } catch (IOException e) {
                return -1;
            }
        }
    }

    private static class ClassDefinitionBuilder {
        private final String name, type, superClass;
        private final List<String> interfaces;
        private final int startLine, endLine;
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

    // ===== 修改 extractAnchors 方法 =====
    private List<AnchorSummary> extractAnchors(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<AnchorSummary> anchors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            // 去除字符串，防止字符串中的伪锚点
            String anchorLine = stripStringsOnly(rawLine);
            var matcher = ANCHOR_PATTERN.matcher(anchorLine);
            if (matcher.find()) {
                String id = null;
                for (int j = 1; j <= matcher.groupCount(); j++) {
                    String candidate = matcher.group(j);
                    if (candidate != null) {
                        id = candidate;
                        break;
                    }
                }
                if (id != null) {
                    // preview 保留原始行（包含注释内容）
                    anchors.add(new AnchorSummary(id, i + 1, rawLine.trim()));
                }
            }
        }
        return anchors;
    }

    /**
     * 去除字符串和字符常量，保留注释和代码。
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

    /**
     * 去除注释和字符串，返回纯净代码行。
     */
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

    private FileStructure parseWithRegex(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<AnchorSummary> anchors = extractAnchors(file);
        String packageName = null;
        List<String> imports = new ArrayList<>();
        List<ClassDefinition> classes = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("package ")) {
                packageName = line.substring(8, line.length() - 1).trim();
            } else if (line.startsWith("import ")) {
                imports.add(line.substring(7, line.length() - 1).trim());
            } else if (line.startsWith("public class ") || line.startsWith("class ")) {
                String[] parts = line.split("\\s+");
                String className = parts[parts.length - 1];
                className = className.replace("{", "").replace("<", " ").split(" ")[0];
                classes.add(new ClassDefinition(
                        className, "class", null, List.of(),
                        List.of(), List.of(), List.of(), -1, -1
                ));
            }
        }

        return new FileStructure(
                file.toString(),
                "Java (regex fallback)",
                packageName,
                imports,
                classes,
                List.of(),
                List.of(),
                anchors
        );
    }
}