package com.myagent.workflow.parser;

import com.myagent.workflow.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class CssParser implements StructureParser {

    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "/\\*\\s*@anchor:\\s*(\\w+)\\s*\\*/"
    );

    // 选择器: .class, #id, element, [attr], 或复合选择器
    private static final Pattern SELECTOR_PATTERN = Pattern.compile(
            "^\\s*([#.\\[\\]\\w\\-:]+)\\s*\\{"
    );

    // @import 语句
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*@import\\s+[^;]+;"
    );

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".css");
    }

    @Override
    public FileStructure parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        List<AnchorSummary> anchors = new ArrayList<>();
        List<MethodDefinition> selectors = new ArrayList<>();  // 用 MethodDefinition 表示选择器
        List<String> imports = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String trimmed = rawLine.trim();

            // 提取锚点
            var anchorMatcher = ANCHOR_PATTERN.matcher(trimmed);
            if (anchorMatcher.find()) {
                String id = anchorMatcher.group(1);
                anchors.add(new AnchorSummary(id, i + 1, trimmed));
                continue; // 锚点行通常不包含其他内容
            }

            // 提取 @import
            var importMatcher = IMPORT_PATTERN.matcher(trimmed);
            if (importMatcher.matches()) {
                imports.add(trimmed);
                continue;
            }

            // 提取选择器
            var selectorMatcher = SELECTOR_PATTERN.matcher(trimmed);
            if (selectorMatcher.matches()) {
                String selector = selectorMatcher.group(1);
                selectors.add(new MethodDefinition(
                        selector,
                        "selector",
                        List.of(),
                        "",
                        i + 1,
                        i + 1,
                        null
                ));
            }
        }

        return new FileStructure(
                file.toString(),
                "CSS",
                null,
                imports,
                List.of(),      // CSS 无类结构
                selectors,      // 选择器作为"函数"
                List.of(),      // CSS 无字段
                anchors
        );
    }

    // ==================== 字符串/注释清理 ====================
    @Override
    public String stripStringsOnly(String rawLine) {
        // CSS 字符串：url("...") 或 '...' 或 "..."
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
        // CSS 注释：/* ... */
        String noStrings = stripStringsOnly(rawLine);
        StringBuilder result = new StringBuilder();
        boolean inBlockComment = false;
        for (int i = 0; i < noStrings.length(); i++) {
            char c = noStrings.charAt(i);
            if (inBlockComment) {
                if (c == '*' && i + 1 < noStrings.length() && noStrings.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < noStrings.length() && noStrings.charAt(i + 1) == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }
}