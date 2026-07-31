package com.myagent.workflow.parser;

import com.myagent.workflow.model.AnchorSummary;
import com.myagent.workflow.model.FileStructure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 通用解析器：仅提取文件中的锚点，不解析结构
 * 作为未支持语言的兜底
 */
public class GenericParser implements StructureParser {

    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "//\\s*@anchor:\\s*(\\w+)|" +
                    "/\\*\\s*@anchor:\\s*(\\w+)\\s*\\*/|" +
                    "<!--\\s*@anchor:\\s*(\\w+)\\s*-->|" +
                    "#\\s*@anchor:\\s*(\\w+)"
    );

    @Override
    public boolean supports(Path file) {
        return true; // 默认支持所有文件
    }

    @Override
    public FileStructure parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<AnchorSummary> anchors = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            var matcher = ANCHOR_PATTERN.matcher(line);
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
                    anchors.add(new AnchorSummary(id, i + 1, line.trim()));
                }
            }
        }

        // 检测语言（通过扩展名简单判断）
        String fileName = file.getFileName().toString().toLowerCase();
        String language = "text";
        if (fileName.endsWith(".java")) language = "Java";
        else if (fileName.endsWith(".py")) language = "Python";
        else if (fileName.endsWith(".js") || fileName.endsWith(".ts")) language = "JavaScript";
        else if (fileName.endsWith(".cpp") || fileName.endsWith(".h")) language = "C++";
        else if (fileName.endsWith(".html") || fileName.endsWith(".htm")) language = "HTML";
        else if (fileName.endsWith(".css")) language = "CSS";
        else if (fileName.endsWith(".md")) language = "Markdown";

        return new FileStructure(
                file.toString(),
                language,
                null,           // packageName
                List.of(),      // imports
                List.of(),      // classes
                List.of(),      // functions
                List.of(),      // fields
                anchors
        );
    }
}