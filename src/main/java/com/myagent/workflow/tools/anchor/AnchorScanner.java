// @anchor: anchorScanner_intro
// 锚点扫描：从源文件抽取锚点、描述与所属符号
package com.myagent.workflow.tools.anchor;

import com.myagent.workflow.model.ClassDefinition;
import com.myagent.workflow.model.FileStructure;
import com.myagent.workflow.model.MethodDefinition;
import com.myagent.workflow.parser.StructureParser;
import com.myagent.workflow.parser.StructureParserRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// @anchor: anchorScanner_class
// 锚点扫描器：从单个文件提取锚点、紧邻描述与所属符号（无状态）
public final class AnchorScanner {

    private AnchorScanner() {}

    public static final List<String> TEXT_EXTENSIONS = List.of(
            ".java", ".html", ".htm", ".css", ".js", ".jsx", ".ts", ".tsx",
            ".txt", ".xml", ".json", ".md", ".properties", ".yml", ".yaml",
            ".sh", ".bat", ".gradle", ".sql",
            ".cpp", ".cc", ".cxx", ".h", ".hpp", ".py", ".pyw");

    static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "^\\s*//\\s*@anchor:\\s*(\\w+)|" +
                    "^\\s*/\\*\\s*@anchor:\\s*(\\w+)\\s*\\*/|" +
                    "^\\s*<!--\\s*@anchor:\\s*(\\w+)\\s*-->|" +
                    "^\\s*#\\s*@anchor:\\s*(\\w+)");
    // @anchor: anchorScanner_anchorIdOnly
    // 宽松匹配：只要求 "@anchor: id"，用于在 XML 注释块内定位锚点
    static final Pattern ANCHOR_ID_ONLY = Pattern.compile("@anchor:\\s*(\\w+)");

    // @anchor: anchorScanner_scan
    // 扫描文件，提取全部 @anchor 的 id/line/preview/desc
    static List<Map<String, Object>> scan(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String ext = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) ext = fileName.substring(dotIdx).toLowerCase();
        if (!TEXT_EXTENSIONS.contains(ext)) return List.of();

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Map<String, Object>> anchors = new ArrayList<>();
        Set<String> localSeen = new HashSet<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);

            // 1) 单行严格匹配：// @anchor: foo、/* @anchor: foo */、<!-- @anchor: foo -->、# @anchor: foo
            Matcher m = ANCHOR_PATTERN.matcher(line);
            if (m.find()) {
                String id = pickGroup(m);
                if (id != null) {
                    addAnchorEntry(anchors, localSeen, id, i, line, extractDesc(lines, i));
                    i++;
                    continue;
                }
            }

            // 2) XML/HTML 跨行注释块：`<!--` 开始、同一行无 `-->`
            int openAt = line.indexOf("<!--");
            if (openAt >= 0 && line.indexOf("-->", openAt + 4) < 0) {
                int endIdx = i + 1;
                while (endIdx < lines.size() && !lines.get(endIdx).contains("-->")) {
                    endIdx++;
                }
                if (endIdx < lines.size()) {
                    // 块内找第一个 @anchor；行号取锚点实际所在行
                    for (int k = i; k <= endIdx; k++) {
                        Matcher am = ANCHOR_ID_ONLY.matcher(lines.get(k));
                        if (am.find()) {
                            String id = am.group(1);
                            String desc = extractXmlBlockDesc(lines, k, endIdx);
                            addAnchorEntry(anchors, localSeen, id, k, lines.get(k), desc);
                            break;   // 一个块只取第一个锚点
                        }
                    }
                    i = endIdx + 1;
                    continue;
                }
            }

            i++;
        }
        return anchors;
    }

    // @anchor: anchorScanner_pickGroup
// 从 ANCHOR_PATTERN 的多捕获组里挑出第一个非空匹配（不同注释形态落在不同组）
    static String pickGroup(Matcher matcher) {
        for (int j = 1; j <= matcher.groupCount(); j++) {
            String c = matcher.group(j);
            if (c != null) return c;
        }
        return null;
    }

    // @anchor: anchorScanner_addAnchorEntry
// 统一构造锚点条目：id 重名追加 _2/_3，行号写 1-based
    static void addAnchorEntry(List<Map<String, Object>> anchors, Set<String> localSeen,
                               String id, int lineIdx, String lineContent, String desc) {
        String finalId = id;
        int suffix = 2;
        while (localSeen.contains(finalId)) finalId = id + "_" + suffix++;
        localSeen.add(finalId);

        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("id", finalId);
        anchor.put("line", lineIdx + 1);
        anchor.put("preview", lineContent.trim());
        anchor.put("desc", desc == null ? "" : desc);
        anchors.add(anchor);
    }

    // @anchor: anchorScanner_extractXmlBlockDesc
// 从 XML/HTML 跨行注释块提取描述：锚点行 @anchor: id 之后 + 后续行直到 -->
    static String extractXmlBlockDesc(List<String> lines, int anchorLineIdx, int endIdx) {
        StringBuilder sb = new StringBuilder();
        for (int j = anchorLineIdx; j <= endIdx && j < lines.size(); j++) {
            String l = lines.get(j);

            // 首行：截掉 @anchor: id 之前的内容和 id 本身
            if (j == anchorLineIdx) {
                int idx = l.indexOf("@anchor:");
                if (idx >= 0) {
                    int p = idx + "@anchor:".length();
                    while (p < l.length() && Character.isWhitespace(l.charAt(p))) p++;
                    while (p < l.length()
                            && (Character.isLetterOrDigit(l.charAt(p)) || l.charAt(p) == '_')) {
                        p++;
                    }
                    l = l.substring(p);
                }
            }

            // 去掉注释标记
            l = l.replace("<!--", "").replace("-->", "").trim();
            if (l.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(l);
        }
        return sb.toString();
    }

    // @anchor: anchorScanner_extractDesc
    // 从锚点下一行紧邻注释提取描述
    static String extractDesc(List<String> lines, int anchorLineIdx) {
        int next = anchorLineIdx + 1;
        if (next >= lines.size()) return "";

        String line = lines.get(next).trim();
        if (line.isEmpty()) return "";
        if (ANCHOR_PATTERN.matcher(line).find()) return "";

        if (line.startsWith("//")) {
            String content = line.substring(2).trim();
            return ANCHOR_PATTERN.matcher(content).find() ? "" : content;
        }
        if (line.startsWith("#") && !line.startsWith("#!")) {
            String content = line.substring(1).trim();
            return ANCHOR_PATTERN.matcher(content).find() ? "" : content;
        }

        if (line.startsWith("/**") || line.startsWith("/*")) {
            String rest = line.startsWith("/**") ? line.substring(3) : line.substring(2);
            rest = rest.trim();
            if (rest.endsWith("*/")) {
                rest = rest.substring(0, rest.length() - 2).trim();
            }
            if (!rest.isEmpty()) return rest;

            for (int i = next + 1; i < lines.size(); i++) {
                String l = lines.get(i).trim();
                if (l.equals("*/") || l.equals("* /")) return "";
                if (ANCHOR_PATTERN.matcher(l).find()) return "";
                if (l.startsWith("*")) l = l.substring(1).trim();
                if (l.endsWith("*/")) l = l.substring(0, l.length() - 2).trim();
                if (!l.isEmpty()) return l;
            }
            return "";
        }

        if (line.startsWith("<!--")) {
            String rest = line.substring(4).trim();
            if (rest.endsWith("-->")) {
                rest = rest.substring(0, rest.length() - 3).trim();
            }
            return rest;
        }

        return "";
    }

    // @anchor: anchorScanner_parseMethods
    // 用语言解析器取出文件内全部方法定义（含类方法）
    static List<MethodDefinition> parseMethods(Path file, StructureParserRegistry registry) {
        StructureParser parser = registry.getParser(file);
        if (parser == null) return List.of();
        try {
            FileStructure structure = parser.parse(file);
            List<MethodDefinition> all = new ArrayList<>();
            if (structure.functions() != null) all.addAll(structure.functions());
            if (structure.classes() != null) {
                for (ClassDefinition c : structure.classes()) {
                    if (c.methods() != null) all.addAll(c.methods());
                }
            }
            return all;
        } catch (Exception ex) {
            return List.of();
        }
    }

    // @anchor: anchorScanner_findSymbol
    // 为锚点行匹配所属或最近的方法符号
    static String findSymbolForAnchor(int anchorLine, List<MethodDefinition> methods) {
        if (methods == null || methods.isEmpty()) return null;
        MethodDefinition best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MethodDefinition m : methods) {
            if (m.startLine() > 0 && m.endLine() > 0
                    && m.startLine() <= anchorLine && anchorLine <= m.endLine()) {
                return m.name();
            }
            if (m.startLine() >= anchorLine) {
                int dist = m.startLine() - anchorLine;
                if (dist < bestDist) { bestDist = dist; best = m; }
            }
        }
        return best != null ? best.name() : null;
    }

    // @anchor: anchorScanner_leanize
    // 将锚点列表精简为 id + line + preview（.anchors.json 专用格式）
    static Map<String, List<Map<String, Object>>> leanize(
            Map<String, List<Map<String, Object>>> projectAnchors) {
        Map<String, List<Map<String, Object>>> lean = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : projectAnchors.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> a : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.get("id"));
                m.put("line", a.get("line"));
                m.put("preview", a.get("preview"));
                list.add(m);
            }
            lean.put(e.getKey(), list);
        }
        return lean;
    }
}