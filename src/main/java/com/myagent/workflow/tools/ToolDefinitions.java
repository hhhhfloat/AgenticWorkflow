package com.myagent.workflow.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具定义构建器 —— 生成 DeepSeek Function Calling 所需的 tools 列表。
 * 从 Main.java 中独立出来，便于单独维护工具 Schema。
 */
// @anchor: toolDefinitions_class
// 工具定义构建器：集中声明可暴露给 Agent 的全部工具 schema
public final class ToolDefinitions {

    private ToolDefinitions() {
        // 工具类，禁止实例化
    }

    /**
     * 构建全部工具定义（符合 DeepSeek Function Calling 规范）。
     * @return 工具定义列表
     */
    // @anchor: toolDefinitions_build
    // 构建全部工具 schema 列表（DeepSeek Function Calling 规范）
    public static List<Map<String, Object>> build() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // @anchor: toolDef_listDirectory
// 工具：list_directory 列出目录树
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
// 工具：write_file 写入/覆盖文件
        tools.add(defineTool(
                "write_file",
                "将源代码（Java/TML/CSS/JS/Python/C++）写入沙箱目录下的指定文件。如果文件已存在则覆盖。",
                defineParams()
                        .prop("filename", "string", "文件名，例如 Tool.java （必须包含 .java 后缀）")
                        .prop("code", "string", "完整的 Java 源代码（包括 package 声明）")
                        .build(),
                List.of("filename", "code")
        ));

        // @anchor: toolDef_compileRun
// 工具：compile_and_run 编译并运行代码
        tools.add(defineTool(
                "compile_and_run",
                "编译并运行文件。Agent 可根据需要仅编译不运行。",
                defineParams()
                        .prop("filename", "string", "文件名或项目相对路径")
                        .prop("mode", "string", "编译模式：html / java / maven / cpp / python / node，默认 auto")
                        .prop("run", "boolean", "是否在编译后立即运行，默认 true。设为 false 可仅编译不运行")
                        .build(),
                List.of("filename")
        ));

        // @anchor: toolDef_getFileStructure
// 工具：get_file_structure 获取代码结构
        tools.add(defineTool(
                "get_file_structure",
                "获取文件的代码结构信息（类、方法、字段、锚点等），帮助快速了解文件内容，而不需要读取整个文件。",
                defineParams()
                        .prop("filename", "string", "文件相对路径，例如 'src/Main.java'")
                        .build(),
                List.of("filename")
        ));

        // @anchor: toolDef_readFile
// 工具：read_file 读取文件内容
        tools.add(defineTool(
                "read_file",
                "读取沙箱目录下指定文件的内容（文本格式），返回文件内容。支持 Java、HTML、TXT 等文本文件。读取大小限制为 5000 字符，超过则截断并提示。",
                defineParams()
                        .prop("filename", "string", "文件名（相对路径），例如 'calculator.html' 或 'src/Tool.java'。")
                        .build(),
                List.of("filename")
        ));

        // @anchor: toolDef_readBetweenAnchors
// 工具：read_between_anchors 按锚点区间读取
        tools.add(defineTool(
                "read_between_anchors",
                "读取两个锚点之间的所有代码内容（包含锚点所在行）。用于精准读取特定代码块，避免读取整个文件。",
                defineParams()
                        .prop("startAnchor", "string", "起始锚点 ID")
                        .prop("endAnchor", "string", "结束锚点 ID")
                        .prop("file", "string", "可选。锚点所在文件的相对路径（如 'js/main.js'），用于跨文件同名锚点的消歧。若锚点 ID 唯一，可省略。")
                        .build(),
                List.of("startAnchor", "endAnchor")
        ));

        // @anchor: toolDef_deleteFile
// 工具：delete_file 删除文件
        tools.add(defineTool(
                "delete_file",
                "删除沙箱目录下的指定文件。请谨慎使用，确认该文件不再需要后再删除。",
                defineParams()
                        .prop("filename", "string", "要删除的文件相对路径，例如 'calculator.html' 或 'src/main/java/com/old/Class.java'。")
                        .build(),
                List.of("filename")
        ));

        // @anchor: toolDef_searchText
// 工具：search_text 全文检索
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
// 工具：build_anchor_index 重建锚点索引
        tools.add(defineTool(
                "build_anchor_index",
                "扫描指定项目目录下的所有文本文件，提取所有 @anchor 注释，重建该项目锚点索引文件（.anchors.json）。通常在修改代码后调用此工具更新索引。",
                defineParams()
                        .prop("project_path", "string", "项目相对路径，如 'cipher-translator'。")
                        .build(),
                List.of("project_path")
        ));


        // @anchor: toolDef_insertAnchor
// 工具：insert_at_anchor 在锚点处插入代码
        tools.add(defineTool(
                "insert_at_anchor",
                "在锚点位置插入代码。position 为 'before' 表示在锚点行之前插入，'after' 表示在锚点行之后插入。",
                defineParams()
                        .prop("anchor_id", "string", "锚点 ID")
                        .prop("content", "string", "要插入的代码内容")
                        .prop("position", "string", "'before' 或 'after'，默认 'after'")
                        .prop("file", "string", "可选。锚点所在文件的相对路径（如 'js/main.js'），用于跨文件同名锚点的消歧。若锚点 ID 唯一，可省略。")
                        .build(),
                List.of("anchor_id", "content")
        ));

        // @anchor: toolDef_deleteBetween
// 工具：delete_between_anchors 删除锚点区间
        tools.add(defineTool(
                "delete_between_anchors",
                "删除从 startAnchor 所在行开始，到 endAnchor 所在行结束之间的所有内容（不包含锚点所在行）。删除后索引自动更新。常用于配合 insert_at_anchor 实现区间替换。",
                defineParams()
                        .prop("startAnchor", "string", "起始锚点 ID")
                        .prop("endAnchor", "string", "结束锚点 ID")
                        .prop("file", "string", "可选。锚点所在文件的相对路径（如 'js/main.js'），用于跨文件同名锚点的消歧。若锚点 ID 唯一，可省略。")
                        .build(),
                List.of("startAnchor", "endAnchor")
        ));

        // @anchor: toolDef_findReferences
// 工具：find_references 查找符号引用
        tools.add(defineTool(
                "find_references",
                "全文匹配：查找符号在项目中的所有出现位置（含定义行、声明、字符串）。返回文件、行号和代码预览。偏向全面覆盖，可能包含非调用点。",
                defineParams()
                        .prop("symbol", "string", "要查找的符号名称，如 'handleNumber'、'renderTimeline'")
                        .prop("path", "string", "搜索起始相对路径，默认为项目根目录")
                        .prop("file_pattern", "string", "文件匹配模式，如 '*.js'，默认为所有代码文件")
                        .build(),
                List.of("symbol")
        ));

        // @anchor: toolDef_findCallers
// 工具：find_callers 查找调用者
        tools.add(defineTool(
                "find_callers",
                "调用形态匹配：查找函数被调用的位置，跳过定义、字符串、注释。识别 NAME( / NAME?.( / NAME.call( / NAME.apply(，并返回所在函数上下文。比 find_references 精准，但字符串内容与无括号回调可能被忽略，需要时可两个工具交叉使用。",
                defineParams()
                        .prop("functionName", "string", "要查找的函数名称")
                        .prop("path", "string", "搜索起始相对路径，默认为项目根目录")
                        .prop("file_pattern", "string", "文件匹配模式，如 '*.js'，默认为所有代码文件")
                        .build(),
                List.of("functionName")
        ));

        // @anchor: toolDef_findCallees
// 工具：find_callees 查找被调用者
        tools.add(defineTool(
                "find_callees",
                "查找函数内部直接调用的所有其他函数。返回被调用函数名称、调用行号和上下文。用于理解函数依赖和重构评估。",
                defineParams()
                        .prop("functionName", "string", "要分析的函数名称")
                        .prop("path", "string", "搜索起始相对路径，默认为项目根目录")
                        .prop("recursive", "boolean", "是否递归分析被调用函数的内部调用，默认为 false")
                        .prop("depth", "integer", "当 recursive 为 true 时的分析深度，默认为 1")
                        .build(),
                List.of("functionName")
        ));

        // @anchor: toolDef_describeAnchors
// 工具：describe_anchors 列出锚点及其职责描述
        tools.add(defineTool(
                "describe_anchors",
                "返回锚点信息。file 支持逗号分隔多个片段。每个片段：先按文件匹配（精确或后缀），命中则列出该文件的全部功能锚点（id / 行号 / symbol / 描述）；未命中则按目录匹配，列出该目录下所有文件的 _intro 锚点描述（文件职责概览）；传 '.' 表示整个项目。不返回 _end 锚点。",
                defineParams()
                        .prop("project_path", "string", "项目相对路径，如 'cipher-translator'。")
                        .prop("file", "string", "必填。文件相对路径（逗号分隔多个文件）或目录路径（如 'src/main/java/game'）。传 '.' 表示整个项目。")
                        .build(),
                List.of("project_path", "file")
        ));

        return tools;
    }

    // ========== 内部构建辅助 ==========
    // @anchor: toolDefinitions_defineTool
// 定义一个工具的 schema（名称/描述/参数）
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

    // @anchor: toolDefinitions_defineParams
// 定义工具的参数对象 schema
    private static ParamsBuilder defineParams() {
        return new ParamsBuilder();
    }

    /** 流畅构建 parameters JSON Schema */
    // @anchor: toolDefinitions_paramsBuilder
// 参数属性构建辅助（类型/描述/必填）
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
