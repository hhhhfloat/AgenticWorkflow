package com.myagent.workflow.parser;

import com.myagent.workflow.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class HtmlParser implements StructureParser {

    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "<!--\\s*@anchor:\\s*(\\w+)\\s*-->"
    );

    // DOCTYPE
    private static final Pattern DOCTYPE_PATTERN = Pattern.compile(
            "^\\s*<!DOCTYPE\\s+([^>]+)>"
    );

    // 开标签: <tag attr="value">
    private static final Pattern OPEN_TAG_PATTERN = Pattern.compile(
            "^\\s*<([\\w-]+)(?:\\s+([^>]*?))?\\s*/?>"
    );

    // 闭标签: </tag>
    private static final Pattern CLOSE_TAG_PATTERN = Pattern.compile(
            "^\\s*</([\\w-]+)\\s*>"
    );

    // 自闭合标签: <tag ... />
    private static final Pattern SELF_CLOSING_TAG_PATTERN = Pattern.compile(
            "^\\s*<([\\w-]+)(?:\\s+([^>]*?))?\\s*/>"
    );

    // 内联脚本: <script> ... </script>
    private static final Pattern SCRIPT_TAG_PATTERN = Pattern.compile(
            "^\\s*<script\\b[^>]*>"
    );

    // 内联样式: <style> ... </style>
    private static final Pattern STYLE_TAG_PATTERN = Pattern.compile(
            "^\\s*<style\\b[^>]*>"
    );

    // 链接: <link ...>
    private static final Pattern LINK_TAG_PATTERN = Pattern.compile(
            "^\\s*<link\\s+([^>]+)>"
    );

    // 提取属性
    private static final Pattern ATTR_PATTERN = Pattern.compile(
            "(\\w+)=\"([^\"]*)\"|(\\w+)='([^']*)'|(\\w+)=([^\\s>]+)"
    );

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".html") || name.endsWith(".htm");
    }

    @Override
    public FileStructure parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String fullContent = String.join("\n", lines);

        List<AnchorSummary> anchors = new ArrayList<>();
        List<String> imports = new ArrayList<>();      // 外部资源
        List<ClassDefinition> classes = new ArrayList<>();  // 用 HTML 结构表示
        List<MethodDefinition> functions = new ArrayList<>(); // 提取的事件处理器
        List<FieldDefinition> fields = new ArrayList<>();     // 提取的 data-* 属性

        // 提取锚点
        var anchorMatcher = ANCHOR_PATTERN.matcher(fullContent);
        while (anchorMatcher.find()) {
            String id = anchorMatcher.group(1);
            // 计算行号（近似）
            int lineNum = 1;
            for (int i = 0; i < anchorMatcher.start(); i++) {
                if (fullContent.charAt(i) == '\n') lineNum++;
            }
            anchors.add(new AnchorSummary(id, lineNum, "<!-- @anchor: " + id + " -->"));
        }

        // 提取外部资源
        Pattern linkPattern = Pattern.compile(
                "<link\\s+[^>]*(?:href|src)=\"([^\"]+)\"[^>]*>"
        );
        var linkMatcher = linkPattern.matcher(fullContent);
        while (linkMatcher.find()) {
            imports.add(linkMatcher.group(1));
        }

        Pattern scriptSrcPattern = Pattern.compile(
                "<script\\s+[^>]*src=\"([^\"]+)\"[^>]*>"
        );
        var scriptMatcher = scriptSrcPattern.matcher(fullContent);
        while (scriptMatcher.find()) {
            imports.add(scriptMatcher.group(1));
        }

        // 提取事件处理器（作为函数信息）
        Pattern eventPattern = Pattern.compile(
                "on(\\w+)\\s*=\\s*\"([^\"]*)\""
        );
        var eventMatcher = eventPattern.matcher(fullContent);
        while (eventMatcher.find()) {
            String eventName = eventMatcher.group(1);
            String handler = eventMatcher.group(2);
            functions.add(new MethodDefinition(
                    "on" + eventName,
                    "?",
                    List.of(),
                    "inline",
                    0,
                    0,
                    null
            ));
        }

        // 提取 HTML 结构作为“类”信息
        // 用栈解析标签结构
        Deque<String> tagStack = new ArrayDeque<>();
        Map<String, Integer> tagCounts = new HashMap<>();

        // 按行处理
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            // 检测开标签
            var openMatcher = OPEN_TAG_PATTERN.matcher(trimmed);
            if (openMatcher.matches()) {
                String tagName = openMatcher.group(1);
                String attrStr = openMatcher.group(2);
                if (!isSelfClosing(tagName)) {
                    tagStack.push(tagName);
                }
                tagCounts.put(tagName, tagCounts.getOrDefault(tagName, 0) + 1);
                continue;
            }

            // 检测闭标签
            var closeMatcher = CLOSE_TAG_PATTERN.matcher(trimmed);
            if (closeMatcher.matches()) {
                String tagName = closeMatcher.group(1);
                if (!tagStack.isEmpty()) {
                    tagStack.pop();
                }
                continue;
            }
        }

        // 用统计信息创建 ClassDefinition（表示 HTML 结构）
        if (!tagCounts.isEmpty()) {
            List<MethodDefinition> tagMethods = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : tagCounts.entrySet()) {
                tagMethods.add(new MethodDefinition(
                        entry.getKey(),
                        String.valueOf(entry.getValue()),
                        List.of(),
                        "element",
                        0,
                        0,
                        null
                ));
            }
            ClassDefinition htmlStruct = new ClassDefinition(
                    "HTMLStructure",
                    "document",
                    null,
                    List.of(),
                    tagMethods,
                    List.of(),
                    List.of(),
                    1,
                    1
            );
            classes.add(htmlStruct);
        }

        // 提取表单字段（作为 fields）
        Pattern inputPattern = Pattern.compile(
                "<input\\s+[^>]*(?:name|id)=\"([^\"]+)\"[^>]*>"
        );
        var inputMatcher = inputPattern.matcher(fullContent);
        while (inputMatcher.find()) {
            fields.add(new FieldDefinition(
                    inputMatcher.group(1),
                    "input",
                    "",
                    0
            ));
        }

        return new FileStructure(
                file.toString(),
                "HTML",
                null,
                imports,
                classes,
                functions,
                fields,
                anchors
        );
    }

    private boolean isSelfClosing(String tagName) {
        return Set.of("area", "base", "br", "col", "embed", "hr", "img", "input",
                "link", "meta", "param", "source", "track", "wbr").contains(tagName.toLowerCase());
    }

    // ==================== 字符串/注释清理 ====================
    @Override
    public String stripStringsOnly(String rawLine) {
        // HTML 没有字符串字面量概念，但属性值用引号包裹
        // 简单处理：移除引号内的内容
        StringBuilder result = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        char prev = 0;
        for (int i = 0; i < rawLine.length(); i++) {
            char c = rawLine.charAt(i);
            if (!inSingle && !inDouble) {
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
            result.append(c);
            prev = c;
            i++;
        }
        return result.toString();
    }

    @Override
    public String cleanLine(String rawLine) {
        // HTML 注释：<!-- ... -->
        String noStrings = stripStringsOnly(rawLine);
        StringBuilder result = new StringBuilder();
        boolean inComment = false;
        for (int i = 0; i < noStrings.length(); i++) {
            char c = noStrings.charAt(i);
            if (!inComment) {
                if (c == '<' && i + 3 < noStrings.length()) {
                    if (noStrings.charAt(i + 1) == '!' &&
                            noStrings.charAt(i + 2) == '-' &&
                            noStrings.charAt(i + 3) == '-') {
                        inComment = true;
                        i += 3;
                        continue;
                    }
                }
                result.append(c);
            } else {
                if (c == '-' && i + 2 < noStrings.length()) {
                    if (noStrings.charAt(i + 1) == '-' &&
                            noStrings.charAt(i + 2) == '>') {
                        inComment = false;
                        i += 2;
                        continue;
                    }
                }
            }
        }
        return result.toString();
    }
}