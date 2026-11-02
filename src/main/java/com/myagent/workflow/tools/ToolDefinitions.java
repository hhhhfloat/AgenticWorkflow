// @anchor: toolDefinitions_intro
// 工具定义：向模型暴露的工具 JSON schema
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
        // 工具-01：list_directory 列出目录树
        tools.add(defineTool(
                "list_directory",
                "列出沙箱目录下指定路径的所有文件和子目录。",
                defineParams()
                        .prop("path", "string", "要列出的相对路径，例如 '.' 表示沙箱根目录。默认为 '.'。")
                        .prop("recursive", "boolean", "是否递归列出所有子目录内容。默认为 false。仅在需要全面了解项目结构时设为 true。")
                        .build(),
                List.of()
        ));

        // @anchor: toolDef_getFileStructure
        // 工具-02：get_file_structure 获取代码结构
        tools.add(defineTool(
                "get_file_structure",
                "获取代码文件的代码结构信息（类、方法、字段、锚点等），帮助快速了解文件内容，而不需要读取整个文件。代码结构支持 java, C++, Python, html, JavaScript, Css。其余返回 describe_anchors 结果",
                defineParams()
                        .prop("filename", "string", "文件相对沙箱根的路径，例如 'project/src/Main.java'")
                        .build(),
                List.of("filename")
        ));

        // @anchor: toolDef_describeAnchors
        // 工具-03：describe_anchors 列出锚点及其职责描述
        tools.add(defineTool(
                "describe_anchors",
                "返回锚点信息。file 支持逗号分隔多个片段。每个片段：先按文件匹配（精确或后缀），命中则列出该文件的全部锚点（含 _end，按行号升序）；未命中则按目录匹配，列出该目录下所有文件的 _intro 锚点描述（文件职责概览）；传 '.' 表示整个项目。",
                defineParams()
                        .prop("project_path", "string", "项目名，相对沙箱根的一级目录，例如 'demo' 或 'cipher-translator'。")
                        .prop("file", "string", "必填。文件路径（相对沙箱根，可逗号分隔多个）或目录路径（如 'src/main/java/game'）。传 '.' 表示整个项目。")
                        .build(),
                List.of("project_path", "file")
        ));

        // @anchor: toolDef_readBetweenAnchors
        // 工具-04：read_between_anchors 读取锚点间内容
        tools.add(defineTool(
                "read_between_anchors",
                "读取两个锚点之间的所有代码内容（包含锚点所在行）。如果 startAnchor 和 endAnchor 传同一个 ID，系统会优先寻找配对的 '_end' 锚点；如果没有配对，会自动读取到该文件的下一个锚点；如果是最后一个锚点则读取到文件末尾。如果起止顺序传反，系统会自动纠正并读取。",
                defineParams()
                        .prop("startAnchor", "string", "起始锚点 ID。如果你想读取某个完整的代码块，可以和 endAnchor 传同样的值（单锚点快捷模式）。")
                        .prop("endAnchor", "string", "结束锚点 ID。如果与 startAnchor 相同，将触发智能配对读取。")
                        .prop("file", "string", "锚点所在文件，相对沙箱根。支持三种形态：完整路径 'demo/src/Main.java'、不带项目名 'src/Main.java'、只写文件名 'Main.java'。锚点 ID 全局唯一时可省略。")
                        .build(),
                List.of("startAnchor", "endAnchor")
        ));

        // @anchor: toolDef_readFile
        // 工具-05：read_file 读取文件内容
        tools.add(defineTool(
                "read_file",
                "返回沙箱目录下指定文本文件的内容（最多返回 50000 字符，超出截断并标记）。不支持目录、以 . 开头的路径；UPDATE.md 不支持整体读取，需改用 read_between_anchors。",
                defineParams()
                        .prop("filename", "string", "文件相对沙箱根的路径，例如 'demo/src/Main.java' 或 'demo/style.css'。")
                        .build(),
                List.of("filename")
        ));

        // @anchor: toolDef_searchText
        // 工具-06：search_text 全文检索
        tools.add(defineTool(
                "search_text",
                "在沙箱目录中搜索指定文本（支持正则表达式），返回匹配的文件路径、行号和内容预览。用于查找代码引用、定位函数调用等。结果限制最多 30 条，超出会提示缩小范围。",
                defineParams()
                        .prop("keyword", "string", "要搜索的关键词（支持正则表达式，如 'parse\\('）")
                        .prop("file_pattern", "string", "文件匹配模式，如 '*.java'、'*.html'，默认为所有文本文件")
                        .prop("path", "string", "搜索起始路径，相对沙箱根。默认为 '.'（沙箱根目录，搜索全部项目）。")
                        .build(),
                List.of("keyword")
        ));

        // @anchor: toolDef_findReferences
        // 工具-07：find_references 查找符号引用
        tools.add(defineTool(
                "find_references",
                "全文匹配：查找符号在项目中的所有出现位置（含定义行、声明、字符串）。返回文件、行号和代码预览。偏向全面覆盖，可能包含非调用点。",
                defineParams()
                        .prop("symbol", "string", "要查找的符号名称，如 'handleNumber'、'renderTimeline'")
                        .prop("path", "string", "搜索起始路径，相对沙箱根。默认为 '.'（沙箱根目录，搜索全部项目）。")
                        .prop("file_pattern", "string", "文件匹配模式，如 '*.js'，默认为所有代码文件")
                        .build(),
                List.of("symbol")
        ));

        // @anchor: toolDef_findCallers
        // 工具-08：find_callers 查找调用者
        tools.add(defineTool(
                "find_callers",
                "调用形态匹配：查找函数被调用的位置，跳过定义、字符串、注释。识别 NAME( / NAME?.( / NAME.call( / NAME.apply(，并返回所在函数上下文。比 find_references 精准，但字符串内容与无括号回调可能被忽略，需要时可两个工具交叉使用。",
                defineParams()
                        .prop("functionName", "string", "要查找的函数名称")
                        .prop("path", "string", "搜索起始路径，相对沙箱根。默认为 '.'（沙箱根目录，搜索全部项目）。")
                        .prop("file_pattern", "string", "文件匹配模式，如 '*.js'，默认为所有代码文件")
                        .build(),
                List.of("functionName")
        ));

        // @anchor: toolDef_findCallees
        // 工具-09：find_callees 查找被调用者
        tools.add(defineTool(
                "find_callees",
                "查找函数内部直接调用的所有其他函数。返回被调用函数名称、调用行号和上下文。用于理解函数依赖和重构评估。",
                defineParams()
                        .prop("functionName", "string", "要分析的函数名称")
                        .prop("path", "string", "搜索起始路径，相对沙箱根。默认为 '.'（沙箱根目录，搜索全部项目）。")
                        .prop("recursive", "boolean", "是否递归分析被调用函数的内部调用，默认为 false")
                        .prop("depth", "integer", "当 recursive 为 true 时的分析深度，默认为 1")
                        .build(),
                List.of("functionName")
        ));

        // @anchor: toolDef_writeFile
        // 工具-10：write_file 写入/覆盖文件
        tools.add(defineTool(
                "write_file",
                "将文本内容写入沙箱目录下的指定文件，文件已存在则覆盖。",
                defineParams()
                        .prop("filename", "string", "文件相对沙箱根的路径，例如 'demo/src/Main.java' 或 'demo/style.css'。")
                        .prop("code", "string", "完整的文件内容")
                        .build(),
                List.of("filename", "code")
        ));

        // @anchor: toolDef_insertAnchor
        // 工具-11: insert_at_anchor 在锚点对应位置插入代码
        tools.add(defineTool(
                "insert_at_anchor",
                "在锚点对应方位插入代码，不会修改锚点自身。如果未指定 position，默认插在锚点之后。",
                defineParams()
                        .prop("anchor_id", "string", "锚点 ID（请传入纯 ID，如 'my_function'，不要带 '@anchor:' 或注释符）")
                        .prop("content", "string", "要插入的代码内容")
                        .prop("position", "string", "'before' 或 'after'（也兼容 append/prepend），不填则默认 'after'")
                        .prop("file", "string", "锚点所在文件，相对沙箱根。支持三种形态：完整路径 'demo/src/Main.java'、不带项目名 'src/Main.java'、只写文件名 'Main.java'。锚点 ID 全局唯一时可省略。")
                        .build(),
                List.of("anchor_id", "content")
        ));

        // @anchor: toolDef_deleteBetween
        // 工具-12：delete_between_anchors 删除锚点间的内容
        tools.add(defineTool(
                "delete_between_anchors",
                "删除从 startAnchor 所在行开始，到 endAnchor 所在行结束之间的所有内容（不包含锚点所在行）。必须传入两个不同的锚点，且顺序必须正确（系统不会自动纠正起止顺序以防误删）。删除后索引自动更新。常用于配合 insert_at_anchor 实现区间替换。",
                defineParams()
                        .prop("startAnchor", "string", "起始锚点 ID（必须明确指定，且位于 endAnchor 之前）")
                        .prop("endAnchor", "string", "结束锚点 ID（必须明确指定，且与 startAnchor 不同）")
                        .prop("file", "string", "锚点所在文件，相对沙箱根。支持三种形态：完整路径 'demo/src/Main.java'、不带项目名 'src/Main.java'、只写文件名 'Main.java'。锚点 ID 全局唯一时可省略。")
                        .build(),
                List.of("startAnchor", "endAnchor")
        ));

        // @anchor: toolDef_deleteFile
        // 工具-13：delete_file 删除文件
        tools.add(defineTool(
                "delete_file",
                "删除沙箱目录下的指定文件。请谨慎使用，确认该文件不再需要后再删除。",
                defineParams()
                        .prop("filename", "string", "要删除的文件，相对沙箱根，例如 'demo/src/Old.java'。")
                        .build(),
                List.of("filename")
        ));

        // @anchor: toolDef_editBetween
        // 工具-14：delete_file 删除文件
        tools.add(defineTool(
                "edit_between_anchors",
                "在锚点区间内，用 new_string 精确替换 old_string。old_string 必须与区间内现有内容完全一致（包括缩进、空白、换行），否则拒绝执行。"
                        + "相比 delete_between_anchors + insert_at_anchor 两步操作，本工具一步原子完成，适合局部小改动（如改几行、修 bug、替换一个值）。"
                        + "若 old_string 在区间内匹配到多处，会返回每处上下文，请按提示加长 old_string 使其唯一。"
                        + "大段重写（整个函数/类重实现）请改用 delete_between_anchors + insert_at_anchor。",
                defineParams()
                        .prop("startAnchor", "string", "起始锚点 ID")
                        .prop("endAnchor", "string", "结束锚点 ID。可与 startAnchor 相同，触发智能配对（优先找对应的 '_end' 锚点）。")
                        .prop("file", "string", "锚点所在文件，相对沙箱根。支持三种形态：完整路径 'demo/src/Main.java'、不带项目名 'src/Main.java'、只写文件名 'Main.java'。锚点 ID 全局唯一时可省略。")
                        .prop("old_string", "string", "要替换的原文片段，必须与文件中内容精确一致（含缩进）。只需包含要改动的部分，不要贴整个函数/整段。")
                        .prop("new_string", "string", "替换后的新内容。空字符串表示删除该片段。")
                        .build(),
                List.of("startAnchor", "endAnchor", "old_string", "new_string")
        ));

        // @anchor: toolDef_compileRun
        // 工具-15：compile_and_run 编译并运行代码
        tools.add(defineTool(
                "compile_and_run",
                "编译并运行文件。可根据需要仅编译不运行。",
                defineParams()
                        .prop("filename", "string", "要编译的文件路径，相对沙箱根，例如 'demo/src/Main.java'。mode=maven 时也可以传项目目录（如 'demo'），其余模式必须是文件路径。")
                        .prop("mode", "string", "编译模式：html / java / maven / cpp / python / node，不填则自动识别")
                        .prop("run", "boolean", "是否在编译后立即运行。默认 false，仅编译不运行。")
                        .build(),
                List.of("filename")
        ));

        // @anchor: toolDef_buildAnchor
        // 工具-16：build_anchor_index 重建锚点索引
        tools.add(defineTool(
                "build_anchor_index",
                "扫描指定项目目录下的所有文本文件，提取所有 @anchor 注释，重建该项目锚点索引文件（.anchors.json）。通常在修改代码后调用此工具更新索引。",
                defineParams()
                        .prop("project_path", "string", "项目名，相对沙箱根的一级目录，例如 'demo' 或 'cipher-translator'。")
                        .build(),
                List.of("project_path")
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
