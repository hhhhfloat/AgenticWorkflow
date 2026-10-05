// @anchor: toolLogFormatter_tot_desc
// 工具日志格式化器：只服务于“人类调试日志”，不参与 API 回灌与 rawfile 落盘
package com.myagent.workflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// @anchor: toolLogFormatter_class
// 工具日志格式化器：把工具参数逐字段渲染，把工具结果按类别截断
/**
 * 工具日志格式化器 —— 只服务于“人类调试日志”。
 * <p>
 * 职责：
 * - 把工具调用参数逐字段渲染为紧凑可读文本，长文本参数截断
 * - 把工具返回结果按工具类别截断到合理长度
 * <p>
 * 明确不负责（必须保持边界）：
 * - 不修改发给模型的 tool 消息内容（API 回灌必须全量）
 * - 不参与 rawfile 落盘（rawfile 保留全量上下文）
 * - 不改变工具实际执行逻辑
 */
public final class ToolLogFormatter {

    private ToolLogFormatter() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ===== 参数展示上限 =====
    // @anchor: toolLogFormatter_paramLimits
    // 长文本参数（content / code）与普通参数各自的展示上限
    private static final int MAX_LONG_PARAM_LEN = 100;
    private static final int MAX_SHORT_PARAM_LEN = 200;
    private static final Set<String> LONG_TEXT_PARAMS = Set.of("content", "code");

    // ===== 结果展示上限 =====
    // @anchor: toolLogFormatter_resultLimits
    // 读入类 / 执行类 / 兜底三类工具结果的展示上限
    private static final int MAX_READ_RESULT_LEN = 300;
    private static final int MAX_EXEC_RESULT_LEN = 500;
    private static final int MAX_DEFAULT_RESULT_LEN = 300;

    // @anchor: toolLogFormatter_toolSets
    // 按展示策略归类的工具名集合
    private static final Set<String> READ_TOOLS = Set.of(
            "read_file", "read_between_anchors", "describe_anchors",
            "get_file_structure", "search_text",
            "find_references", "find_callers", "find_callees"
    );

    private static final Set<String> EXEC_TOOLS = Set.of("compile_and_run");

    // ==================== 参数 ====================

    // @anchor: toolLogFormatter_formatArgs
    // 把工具参数 JSON 逐字段渲染为紧凑文本；解析失败时退化为整体截断
    public static String formatArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return "(空)";
        try {
            JsonNode root = MAPPER.readTree(argumentsJson);
            if (!root.isObject()) {
                return truncate(argumentsJson, MAX_SHORT_PARAM_LEN);
            }
            List<String> parts = new ArrayList<>();
            var fields = root.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                parts.add(e.getKey() + "=" + formatValue(e.getKey(), e.getValue()));
            }
            return parts.isEmpty() ? "(空)" : String.join(", ", parts);
        } catch (Exception ex) {
            // 解析失败：退化为整体截断，保证日志不断
            return truncate(argumentsJson, MAX_SHORT_PARAM_LEN);
        }
    }

    // @anchor: toolLogFormatter_formatValue
    // 按参数名决定展示上限：长文本参数用小上限，其余用大上限
    private static String formatValue(String key, JsonNode value) {
        String raw = value.isTextual() ? value.asText() : value.toString();
        int limit = LONG_TEXT_PARAMS.contains(key) ? MAX_LONG_PARAM_LEN : MAX_SHORT_PARAM_LEN;
        return truncate(raw, limit);
    }

    // ==================== 结果 ====================

    // @anchor: toolLogFormatter_formatResult
    // 按工具类别把结果截断到对应上限
    public static String formatResult(String functionName, String result) {
        if (result == null) return "（工具返回 null）";
        return truncate(result, maxResultLenFor(functionName));
    }

    // @anchor: toolLogFormatter_maxResultLenFor
    // 根据工具名返回结果展示上限
    private static int maxResultLenFor(String functionName) {
        if (functionName == null) return MAX_DEFAULT_RESULT_LEN;
        if (EXEC_TOOLS.contains(functionName)) return MAX_EXEC_RESULT_LEN;
        if (READ_TOOLS.contains(functionName)) return MAX_READ_RESULT_LEN;
        return MAX_DEFAULT_RESULT_LEN;
    }

    // ==================== 通用截断 ====================

    // @anchor: toolLogFormatter_truncate
    // 超长字符串截断并附总长提示
    private static String truncate(String s, int limit) {
        if (s == null) return "";
        if (s.length() <= limit) return s;
        return s.substring(0, limit) + "...[共 " + s.length() + " 字符]";
    }
}