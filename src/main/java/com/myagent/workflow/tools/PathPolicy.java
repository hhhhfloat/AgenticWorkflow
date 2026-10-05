// @anchor: pathPolicy_intro
// 工具路径策略：沙箱边界、工作项目限定与 UPDATE.md 特殊规则
package com.myagent.workflow.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;

// @anchor: pathPolicy_class
// 路径策略：按工具名校验路径参数与工作项目限定
public class PathPolicy {

    // @anchor: pathPolicy_writeTools
    // 写操作工具集合：目标必须落在 workProject 内
    private static final Set<String> WRITE_TOOLS = Set.of(
            "write_file", "delete_file", "compile_and_run", "build_anchor_index"
    );

    // @anchor: pathPolicy_extraWritePaths
    // 工作项目之外仍允许写入的公共路径（相对沙箱）
    private static final Set<String> EXTRA_WRITE_PATHS = Set.of("tmp");

    // @anchor: pathPolicy_pathArgMap
    // 工具名 → 需要做路径检查的参数名
    private static final Map<String, List<String>> PATH_ARG_MAP = Map.ofEntries(
            Map.entry("read_file",          List.of("filename")),
            Map.entry("write_file",         List.of("filename")),
            Map.entry("delete_file",        List.of("filename")),
            Map.entry("get_file_structure", List.of("filename")),
            Map.entry("compile_and_run",    List.of("filename")),
            Map.entry("list_directory",     List.of("path")),
            Map.entry("search_text",        List.of("path")),
            Map.entry("find_references",    List.of("path")),
            Map.entry("find_callers",       List.of("path")),
            Map.entry("find_callees",       List.of("path")),
            Map.entry("describe_anchors",   List.of("project_path", "file"))
    );

    private final String workProject;

    // @anchor: pathPolicy_constructor
    public PathPolicy(String workProject) {
        this.workProject = workProject;
    }

    // @anchor: pathPolicy_check
    // 统一入口：先校验路径参数，再校验工作项目限定；通过返回 null
    public String check(String toolName, Args args) {
        List<String> pathArgs = PATH_ARG_MAP.get(toolName);
        if (pathArgs != null) {
            for (String argName : pathArgs) {
                String path = args.getString(argName);
                if (path == null || path.isBlank()) continue;
                String err = checkPath(toolName, path);
                if (err != null) return err;
            }
        }
        return checkWorkProject(toolName, args);
    }

    // @anchor: pathPolicy_checkWorkProject
    // 写工具的工作项目限定校验：目标路径必须落在 workProject 内
    private String checkWorkProject(String toolName, Args args) {
        if (!WRITE_TOOLS.contains(toolName)) return null;

        // 未绑定工作项目：只允许写 tmp/
        if (workProject == null) {
            String filename = args.getString("filename");
            if (filename == null || filename.isBlank()) {
                if ("build_anchor_index".equals(toolName)) {
                    return "❌ 当前会话未绑定工作项目，无法重建锚点索引。请先选择项目后重试。";
                }
                return null;
            }
            String normalized = normalize(filename);
            for (String extra : EXTRA_WRITE_PATHS) {
                if (normalized.equals(extra) || normalized.startsWith(extra + "/")) {
                    return null;
                }
            }
            return "❌ 当前会话未绑定工作项目，只能写入 tmp/ 临时区。\n"
                    + "  请在会话中选择或新建一个项目，再执行写操作。";
        }

        // build_anchor_index：project_path 精确等于 workProject
        if ("build_anchor_index".equals(toolName)) {
            String pp = args.getString("project_path");
            if (pp == null || !pp.equals(workProject)) {
                return "❌ 只能操作工作项目 [" + workProject + "]，当前: " + pp;
            }
            return null;
        }

        // 其他写工具：filename 必须落在 workProject 下
        String filename = args.getString("filename");
        if (filename == null || filename.isBlank()) return null;

        String normalized = normalize(filename);
        if (normalized.equals(workProject) || normalized.startsWith(workProject + "/")) {
            return null;
        }
        for (String extra : EXTRA_WRITE_PATHS) {
            if (normalized.equals(extra) || normalized.startsWith(extra + "/")) {
                return null;
            }
        }
        return "❌ 只能写入工作项目 [" + workProject + "] 或临时目录，当前路径: " + filename;
    }

    // @anchor: pathPolicy_checkPath
    // 沙箱路径校验：拒绝 ".." 穿越、点开头路径与 UPDATE.md 危险读取/删除
    private String checkPath(String toolName, String path) {
        String normalized = normalize(path);
        String[] segments = normalized.split("/");

        for (String seg : segments) {
            if ("..".equals(seg)) {
                return "❌ 路径中不允许出现 \"..\"：" + path;
            }
            if (seg.startsWith(".") && !seg.equals(".")) {
                return "❌ 不允许访问以 . 开头的文件/目录：" + path
                        + "。如需项目结构信息，请使用 describe_anchors / get_file_structure。";
            }
        }

        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        if ("UPDATE.md".equalsIgnoreCase(fileName)) {
            return switch (toolName) {
                case "read_file" -> "❌ UPDATE.md 不支持完整读取。请使用 read_between_anchors 按锚点读取。";
                case "delete_file" -> "❌ UPDATE.md 不允许删除。";
                default -> null;
            };
        }
        return null;
    }

    // @anchor: pathPolicy_normalize
    // 反斜杠统一为 /，去掉开头的 "./"
    private static String normalize(String path) {
        String n = path.replace('\\', '/');
        if (n.startsWith("./")) n = n.substring(2);
        return n;
    }
}