package com.myagent.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @anchor: toolDefinitions_class
 * 工具定义构建器 —— 生成 DeepSeek Function Calling 所需的 tools 列表。
 * 从 Main.java 中独立出来，便于单独维护工具 Schema。
 */
public final class ToolDefinitions {

    private ToolDefinitions() {
        // 工具类，禁止实例化
    }

    /**
     * @anchor: toolDefinitions_build
     * 构建全部工具定义（符合 DeepSeek Function Calling 规范）。
     * @return 工具定义列表
     */
    public static List<Map<String, Object>> build() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // @anchor: toolDef_listDirectory
        tools.add(defineTool(
                "list_directory",
                "列出沙箱目录下指定路径的所有文件和子目录。如果 recursive 为 true，则递归列出所有层级（慎用，仅当项目较小时使用）。",
                defineParams()
                        .prop("path", "string", "要列出的相对路径，例如 '.' 表示沙箱根目录。默认为 '.'。")
                        .prop("recursive", "boolean", "是否递归列出所有子目录内容。默认为 false。仅在需要全面了解项目结构时设为 true。")
                        .build(),
                List.of()
        ));

        // @anchor: toolDef_writeFile
        tools.add(defineTool(
                "write_java_file",
                "将源代码（Java 或 HTML/CSS/JS）写入沙箱目录下的指定文件。如果文件已存在则覆盖。",
                defineParams()
                        .prop("filename", "string", "文件名，例如 Tool.java （必须包含 .java 后缀）")
                        .prop("code", "string", "完整的 Java 源代码（包括 package 声明）")
                        .build(),
                List.of("filename", "code")
        ));

        // @anchor: toolDef_compileRun
        tools.add(defineTool(
                "compile_and_run",
                "验证并运行/预览沙箱目录下的文件。如果文件是 .java 则编译并运行；如果文件是 .html 则在浏览器中打开预览。",
                defineParams()
                        .prop("filename", "string", "要执行的 Java 文件名（例如 Tool.java）")
                        .build(),
                List.of("filename")
        ));

        // @anchor: toolDef_readFile
        tools.add(defineTool(
                "read_file",
                "读取沙箱目录下指定文件的内容（文本格式），返回文件内容。支持 Java、HTML、TXT 等文本文件。读取大小限制为 5000 字符，超过则截断并提示。",
                defineParams()
                        .prop("filename", "string", "文件名（相对路径），例如 'calculator.html' 或 'src/Tool.java'。")
                        .build(),
                List.of("filename")
        ));

        // @anchor: toolDef_deleteFile
        tools.add(defineTool(
                "delete_file",
                "删除沙箱目录下的指定文件。请谨慎使用，确认该文件不再需要后再删除。",
                defineParams()
                        .prop("filename", "string", "要删除的文件相对路径，例如 'calculator.html' 或 'src/main/java/com/old/Class.java'。")
                        .build(),
                List.of("filename")
        ));

        // @anchor: toolDef_searchText
        tools.add(defineTool(
                "search_text",
                "在沙箱目录中搜索指定文本（支持正则表达式），返回匹配的文件路径、行号和内容预览。用于查找代码引用、定位函数调用等。结果限制最多 30 条，超出会提示缩小范围。",
                defineParams()
                        .prop("keyword", "string", "要搜索的关键词（支持正则表达式，如 'parse\\('）")
                        .prop("file_pattern", "string", "文件匹配模式，如 '*.java'、'*.html'，默认为所有文本文件")
                        .prop("path", "string", "搜索起始相对路径，默认为 '.'（沙箱根目录）")
                        .build(),
                List.of("keyword")
        ));

        // @anchor: toolDef_buildAnchor
        tools.add(defineTool(
                "build_anchor_index",
                "扫描指定项目目录下的所有文本文件，提取所有 @anchor 注释，重建锚点索引文件。通常在修改代码后调用此工具更新索引。",
                defineParams()
                        .prop("project_path", "string", "项目相对路径，如 'cipher-translator'。如果为空，则扫描整个沙箱。")
                        .build(),
                List.of("project_path")
        ));

        // @anchor: toolDef_listAnchors
        tools.add(defineTool(
                "list_anchors",
                "列出指定项目中的所有锚点，返回锚点ID、所在文件、行号和内容预览。",
                defineParams()
                        .prop("project_path", "string", "项目相对路径，如 'cipher-translator'。")
                        .build(),
                List.of("project_path")
        ));

        // @anchor: toolDef_insertAnchor
        tools.add(defineTool(
                "insert_at_anchor",
                "在锚点位置插入代码。position 为 'before' 表示在锚点行之前插入，'after' 表示在锚点行之后插入。",
                defineParams()
                        .prop("anchor_id", "string", "锚点 ID")
                        .prop("content", "string", "要插入的代码内容")
                        .prop("position", "string", "'before' 或 'after'，默认 'after'")
                        .build(),
                List.of("anchor_id", "content")
        ));

        // @anchor: toolDef_deleteBetween
        tools.add(defineTool(
                "delete_between_anchors",
                "删除从 startAnchor 所在行开始，到 endAnchor 所在行结束之间的所有内容（不包含锚点所在行）。删除后索引自动更新。常用于配合 insert_at_anchor 实现区间替换。",
                defineParams()
                        .prop("startAnchor", "string", "起始锚点 ID")
                        .prop("endAnchor", "string", "结束锚点 ID")
                        .build(),
                List.of("startAnchor", "endAnchor")
        ));

        return tools;
    }

    // ========== 内部构建辅助 ==========

    private static Map<String, Object> defineTool(String name, String description,
                                                   Map<String, Object> parameters, List<String> required) {
        parameters.put("required", required);
        Map<String, Object> func = new HashMap<>();
        func.put("name", name);
        func.put("description", description);
        func.put("parameters", parameters);

        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        tool.put("function", func);
        return tool;
    }

    private static ParamsBuilder defineParams() {
        return new ParamsBuilder();
    }

    /** 流畅构建 parameters JSON Schema */
    private static class ParamsBuilder {
        private final Map<String, Object> props = new HashMap<>();

        ParamsBuilder prop(String name, String type, String description) {
            props.put(name, Map.of("type", type, "description", description));
            return this;
        }

        Map<String, Object> build() {
            Map<String, Object> params = new HashMap<>();
            params.put("type", "object");
            params.put("properties", props);
            return params;
        }
    }
}
