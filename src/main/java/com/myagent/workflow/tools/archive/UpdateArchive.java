// @anchor: updateArchive_intro
// 更新档案馆：业务逻辑层，负责 .update/ 目录的读写、合并、移动与索引维护
package com.myagent.workflow.tools.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myagent.workflow.tools.FileOperator;
import com.myagent.workflow.tools.PathUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

// @anchor: updateArchive_class
public class UpdateArchive {
    private final ObjectMapper mapper;
    private final String workProject;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // @anchor: updateArchive_projectLocks
    // 项目级静态锁表：同一项目的所有实例共享一把锁，兜底"用户双 tab 并发"场景
    // Agent 与用户的并发已由 UpdateLogActionHandler 的 409 检查拦截，这里只防双 tab
    private static final ConcurrentHashMap<String, Object> PROJECT_LOCKS = new ConcurrentHashMap<>();

    private Object lock() {
        return PROJECT_LOCKS.computeIfAbsent(workProject == null ? "" : workProject, k -> new Object());
    }

    public UpdateArchive(ObjectMapper mapper, String workProject) {
        this.mapper = mapper;
        this.workProject = workProject;
    }

    // @anchor: updateArchive_getUpdateDir
    // 获取（并创建）当前项目的 .update 目录（用于写操作）
    private Path getUpdateDir() throws IOException {
        Path updateDir = resolveUpdateDir();
        if (!Files.exists(updateDir)) {
            Files.createDirectories(updateDir);
        }
        return updateDir;
    }

    // @anchor: updateArchive_resolveUpdateDir
    // 仅解析路径，不创建目录（用于读操作，避免只读产生副作用）
    private Path resolveUpdateDir() throws IOException {
        if (workProject == null || workProject.isBlank()) {
            throw new IOException("未绑定工作项目，无法使用更新档案馆");
        }
        Path projectDir = PathUtils.safeResolve(workProject);
        return projectDir.resolve(".update");
    }

    // @anchor: updateArchive_appendLog
    // 追加更新记录。如果 thread 不存在会自动创建；如果已 closed，会自动激活。
    public String appendLog(String threadId, String title, String summary, String details, String filesStr,
            String status, String timestamp) {
        synchronized (lock()) {
            if (threadId == null || threadId.isBlank())
                return "❌ threadId 不能为空";
            if (!threadId.matches("^[a-zA-Z0-9_]+$"))
                return "❌ threadId 只能包含字母、数字和下划线";
            if (summary == null || summary.isBlank())
                return "❌ summary 不能为空";

            if (status != null && !status.isBlank()
                    && !"active".equals(status) && !"closed".equals(status)) {
                return "❌ status 只能是 'active' 或 'closed'，收到: " + status;
            }

            try {
                Path updateDir = getUpdateDir();
                Path threadFile = updateDir.resolve("thread_" + threadId + ".json");

                ObjectNode threadNode;
                boolean isNew = false;
                if (Files.exists(threadFile)) {
                    threadNode = (ObjectNode) mapper.readTree(threadFile.toFile());
                    // 自动激活逻辑
                    if ("closed".equals(threadNode.path("status").asText()) && status == null) {
                        threadNode.put("status", "active");
                    }
                } else {
                    if (title == null || title.isBlank()) {
                        return "❌ 首次创建线程 [" + threadId + "] 时必须提供 title";
                    }
                    threadNode = mapper.createObjectNode();
                    threadNode.put("thread_id", threadId);
                    threadNode.put("title", title);
                    threadNode.put("status", status != null ? status : "active");
                    // 创建时间也支持自定义
                    String createDate = (timestamp != null && timestamp.length() >= 10)
                            ? timestamp.substring(0, 10)
                            : LocalDateTime.now().format(DATE_FMT);
                    threadNode.put("created_at", createDate);
                    threadNode.putArray("updates");
                    isNew = true;
                }

                ArrayNode updates = (ArrayNode) threadNode.get("updates");
                ObjectNode update = mapper.createObjectNode();

                // 使用自定义时间戳，否则用当前时间
                String ts = (timestamp != null && !timestamp.isBlank())
                        ? timestamp.trim()
                        : LocalDateTime.now().format(TIME_FMT);
                update.put("timestamp", ts);
                update.put("summary", summary);
                if (details != null && !details.isBlank())
                    update.put("details", details);

                if (filesStr != null && !filesStr.isBlank()) {
                    ArrayNode filesArr = mapper.createArrayNode();
                    for (String f : filesStr.split(",")) {
                        String t = f.trim();
                        if (!t.isEmpty())
                            filesArr.add(t);
                    }
                    if (filesArr.size() > 0) {
                        update.set("files", filesArr);
                    }
                    update.set("files", filesArr);
                }
                updates.add(update);

                if (status != null && !status.isBlank()) {
                    threadNode.put("status", status);
                }

                sortUpdates(updates);
                saveThread(threadFile, threadNode);
                rebuildIndex(updateDir);

                return "✅ 已" + (isNew ? "创建线程" : "追加更新记录到线程") + " [" + threadId + "]";

            } catch (Exception e) {
                return "❌ 写入更新档案失败: " + e.getMessage();
            }
        }
    }

    // @anchor: updateArchive_queryLog
    // 查询记录：返回人类/模型友好的纯文本
    public String queryLog(String threadId, String keyword, String latestNumber) {
        try {
            Path updateDir = resolveUpdateDir();
            Path indexFile = updateDir.resolve("index.json");

            // ===== 新增：全局关键词搜索分支 =====
            if (keyword != null && !keyword.isBlank()) {
                if (!Files.exists(updateDir))
                    return "📭 暂无更新档案";
                List<JsonNode> allMatches = new ArrayList<>();
                String kw = keyword.trim().toLowerCase();

                try (var stream = Files.list(updateDir)) {
                    var files = stream.filter(
                            p -> p.getFileName().toString().startsWith("thread_") && p.toString().endsWith(".json"))
                            .toList();
                    for (Path tf : files) {
                        ObjectNode t = (ObjectNode) mapper.readTree(tf.toFile());
                        String tid = t.get("thread_id").asText();
                        String tTitle = t.get("title").asText();
                        for (JsonNode u : t.get("updates")) {
                            String uText = (u.path("summary").asText() + " " + u.path("details").asText() + " "
                                    + u.path("files").toString()).toLowerCase();
                            if (uText.contains(kw)) {
                                // 为了展示方便，把 thread 信息塞进副本里
                                ObjectNode match = u.deepCopy();
                                match.put("_thread_id", tid);
                                match.put("_thread_title", tTitle);
                                allMatches.add(match);
                            }
                        }
                    }
                }

                if (allMatches.isEmpty())
                    return "🔍 未找到包含「" + keyword + "」的记录。";

                // 按时间倒序排
                allMatches.sort((a, b) -> b.path("timestamp").asText().compareTo(a.path("timestamp").asText()));

                // 解析 String 类型的 latestNumber 决定搜索结果上限
                int searchLimit = 20; // 全局搜索默认返回 20 条
                if (latestNumber != null && !latestNumber.isBlank()) {
                    String s = latestNumber.trim();
                    // 全局搜索不支持单点定位，如果传了 "#5"，剥离 "#" 当条数上限用
                    if (s.startsWith("#"))
                        s = s.substring(1);
                    try {
                        int parsed = Integer.parseInt(s);
                        if (parsed > 0)
                            searchLimit = parsed;
                    } catch (NumberFormatException ignored) {
                    }
                }

                int showCount = Math.min(allMatches.size(), searchLimit);

                StringBuilder sb = new StringBuilder();
                sb.append("🔍 找到 ").append(allMatches.size()).append(" 条包含「").append(keyword).append("」的记录（展示前 ")
                        .append(showCount).append(" 条）：\n\n");
                for (int i = 0; i < showCount; i++) {
                    JsonNode u = allMatches.get(i);
                    sb.append("[").append(u.path("timestamp").asText()).append("] ")
                            .append("[").append(u.path("_thread_id").asText()).append("] ")
                            .append(u.path("summary").asText()).append("\n");
                    if (u.has("details"))
                        sb.append("  详情: ").append(u.path("details").asText().replace("\n", "\n        ")).append("\n");
                    sb.append("\n");
                }
                return sb.toString();
            }

            if (threadId == null || threadId.isBlank()) {
                if (!Files.exists(indexFile))
                    return "📭 暂无更新档案（还没有任何记录）";

                ObjectNode indexNode = (ObjectNode) mapper.readTree(indexFile.toFile());
                ArrayNode threads = (ArrayNode) indexNode.get("threads");
                if (threads.size() == 0)
                    return "📭 更新档案为空";

                // 【新增】概览按 last_updated 倒序排列
                List<JsonNode> list = new ArrayList<>();
                for (JsonNode t : threads)
                    list.add(t);
                list.sort((a, b) -> b.path("last_updated").asText().compareTo(a.path("last_updated").asText()));

                StringBuilder sb = new StringBuilder();
                sb.append("📚 更新档案概览（共 ").append(list.size()).append(" 个主题线程）：\n\n");
                for (JsonNode t : list) {
                    sb.append("- [").append(t.get("thread_id").asText()).append("] ")
                            .append(t.get("title").asText())
                            .append(" (").append(t.get("status").asText()).append(") | ")
                            .append("最后更新: ").append(t.get("last_updated").asText()).append(" | ")
                            .append("共 ").append(t.get("update_count").asInt()).append(" 条记录\n");
                }
                sb.append("\n💡 提示：传入具体的 `threadId` 可查看该线程的最近记录。");
                return sb.toString();
            }

            // ===== 3. 明细分支 =====
            Path threadFile = updateDir.resolve("thread_" + threadId + ".json");
            if (!Files.exists(threadFile))
                return "❌ 未找到线程: " + threadId;
            ObjectNode threadNode = (ObjectNode) mapper.readTree(threadFile.toFile());
            ArrayNode ups = (ArrayNode) threadNode.get("updates");

            // 解析二分指令
            int limit = 3; // 默认全列 3 条
            int singleIndex = -1; // >0 时代表单点查询
            if (latestNumber != null && !latestNumber.isBlank()) {
                String s = latestNumber.trim();
                try {
                    if (s.startsWith("#")) {
                        singleIndex = Integer.parseInt(s.substring(1));
                    } else {
                        limit = Integer.parseInt(s);
                    }
                } catch (NumberFormatException ignored) {
                    // 格式错误则回退默认值
                }
            }

            // 单点精确查询
            if (singleIndex > 0) {
                if (singleIndex > ups.size())
                    return "❌ 索引越界：" + threadId + " 只有 " + ups.size() + " 条记录。";
                ObjectNode u = (ObjectNode) ups.get(singleIndex - 1); // 1-based 转 0-based
                StringBuilder sb = new StringBuilder();
                sb.append("📚 线程 [").append(threadId).append("] 第 ").append(singleIndex).append(" 条更新：\n\n");
                sb.append("[").append(u.get("timestamp").asText()).append("] ")
                        .append(u.get("summary").asText()).append("\n");
                if (u.has("details")) {
                    String details = u.get("details").asText();
                    sb.append("  详情:\n        ").append(details.replace("\n", "\n        ")).append("\n");
                }
                if (u.has("files") && u.get("files").size() > 0) {
                    sb.append("  文件: ");
                    for (JsonNode f : u.get("files"))
                        sb.append(f.asText()).append(", ");
                    sb.setLength(sb.length() - 2);
                    sb.append("\n");
                }
                return sb.toString();
            }

            // 范围查询（全列 N 条）
            int safeLimit = Math.max(1, limit);
            int end = Math.min(ups.size(), safeLimit);

            StringBuilder sb = new StringBuilder();
            sb.append("📚 线程 [").append(threadId).append("]（")
                    .append(threadNode.get("title").asText()).append("）最近 ")
                    .append(end).append(" 条更新：\n\n");

            for (int i = 0; i < end; i++) {
                ObjectNode u = (ObjectNode) ups.get(i);
                // 带上 [#序号]，方便后续单点查询
                sb.append("[#").append(i + 1).append("] [").append(u.get("timestamp").asText()).append("] ")
                        .append(u.get("summary").asText()).append("\n");
                if (u.has("details")) {
                    String details = u.get("details").asText();
                    sb.append("  详情: ").append(details.replace("\n", "\n        ")).append("\n");
                }
                if (u.has("files") && u.get("files").size() > 0) {
                    sb.append("  文件: ");
                    for (JsonNode f : u.get("files"))
                        sb.append(f.asText()).append(", ");
                    sb.setLength(sb.length() - 2);
                    sb.append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();

        } catch (Exception e) {
            return "❌ 读取更新档案失败: " + e.getMessage();
        }
    }

    // ===== 前端操作接口 =====

    // @anchor: updateArchive_deleteUpdate
    // 删除指定索引的更新记录
    public synchronized String deleteUpdate(String threadId, int updateIndex) {
        try {
            Path updateDir = getUpdateDir();
            Path threadFile = updateDir.resolve("thread_" + threadId + ".json");
            if (!Files.exists(threadFile))
                return "❌ 线程不存在";

            ObjectNode threadNode = (ObjectNode) mapper.readTree(threadFile.toFile());
            ArrayNode updates = (ArrayNode) threadNode.get("updates");
            if (updateIndex < 0 || updateIndex >= updates.size())
                return "❌ 索引越界";

            updates.remove(updateIndex);
            saveThread(threadFile, threadNode);
            rebuildIndex(updateDir);
            return "✅ 已删除记录";
        } catch (Exception e) {
            return "❌ 删除失败: " + e.getMessage();
        }
    }

    // @anchor: updateArchive_moveUpdate
    // 移动记录到另一个线程
    public synchronized String moveUpdate(String fromThread, String toThread, int updateIndex) {
        if (fromThread.equals(toThread))
            return "❌ 源线程和目标线程相同";
        try {
            Path updateDir = getUpdateDir();
            Path fromFile = updateDir.resolve("thread_" + fromThread + ".json");
            Path toFile = updateDir.resolve("thread_" + toThread + ".json");
            if (!Files.exists(fromFile) || !Files.exists(toFile))
                return "❌ 线程不存在";

            ObjectNode fromNode = (ObjectNode) mapper.readTree(fromFile.toFile());
            ObjectNode toNode = (ObjectNode) mapper.readTree(toFile.toFile());

            ArrayNode fromUpdates = (ArrayNode) fromNode.get("updates");
            ArrayNode toUpdates = (ArrayNode) toNode.get("updates");
            if (updateIndex < 0 || updateIndex >= fromUpdates.size())
                return "❌ 索引越界";

            JsonNode targetUpdate = fromUpdates.remove(updateIndex);
            toUpdates.add(targetUpdate);

            sortUpdates(fromUpdates);
            sortUpdates(toUpdates);
            saveThread(fromFile, fromNode);
            saveThread(toFile, toNode);
            rebuildIndex(updateDir);
            return "✅ 记录已移动";
        } catch (Exception e) {
            return "❌ 移动失败: " + e.getMessage();
        }
    }

    // @anchor: updateArchive_mergeThread
    // 合并两个线程
    public synchronized String mergeThread(String fromThread, String toThread, String newTitle) {
        if (fromThread.equals(toThread))
            return "❌ 不能合并到自身";
        try {
            Path updateDir = getUpdateDir();
            Path fromFile = updateDir.resolve("thread_" + fromThread + ".json");
            Path toFile = updateDir.resolve("thread_" + toThread + ".json");
            if (!Files.exists(fromFile) || !Files.exists(toFile))
                return "❌ 线程不存在";

            ObjectNode fromNode = (ObjectNode) mapper.readTree(fromFile.toFile());
            ObjectNode toNode = (ObjectNode) mapper.readTree(toFile.toFile());

            ArrayNode toUpdates = (ArrayNode) toNode.get("updates");
            for (JsonNode u : fromNode.get("updates")) {
                toUpdates.add(u);
            }
            if (newTitle != null && !newTitle.isBlank()) {
                toNode.put("title", newTitle);
            }

            sortUpdates(toUpdates);
            saveThread(toFile, toNode);
            Files.delete(fromFile);
            rebuildIndex(updateDir);
            return "✅ 线程已合并并删除源线程";
        } catch (Exception e) {
            return "❌ 合并失败: " + e.getMessage();
        }
    }

    // @anchor: updateArchive_setStatus
    // 修改线程状态
    public synchronized String setStatus(String threadId, String status) {
        if (!"active".equals(status) && !"closed".equals(status))
            return "❌ 状态只能是 active 或 closed";
        try {
            Path updateDir = getUpdateDir();
            Path threadFile = updateDir.resolve("thread_" + threadId + ".json");
            if (!Files.exists(threadFile))
                return "❌ 线程不存在";

            ObjectNode threadNode = (ObjectNode) mapper.readTree(threadFile.toFile());
            threadNode.put("status", status);
            saveThread(threadFile, threadNode);
            rebuildIndex(updateDir);
            return "✅ 状态已更新为 " + status;
        } catch (Exception e) {
            return "❌ 修改状态失败: " + e.getMessage();
        }
    }

    // @anchor: updateArchive_editUpdate
    // 编辑指定索引的单条记录（不改 timestamp，保持原始发生时间）
    public String editUpdate(String threadId, int updateIndex, String summary,
            String details, String filesStr) {
        if (summary == null || summary.isBlank())
            return "❌ summary 不能为空";
        try {
            Path updateDir = getUpdateDir();
            Path threadFile = updateDir.resolve("thread_" + threadId + ".json");
            if (!Files.exists(threadFile))
                return "❌ 未找到线程: " + threadId;

            ObjectNode threadNode = (ObjectNode) mapper.readTree(threadFile.toFile());
            ArrayNode updates = (ArrayNode) threadNode.get("updates");
            if (updateIndex < 0 || updateIndex >= updates.size()) {
                return "❌ 索引越界：" + threadId + " 只有 " + updates.size() + " 条记录";
            }

            ObjectNode update = (ObjectNode) updates.get(updateIndex);
            update.put("summary", summary);

            if (details != null && !details.isBlank()) {
                update.put("details", details);
            } else {
                update.remove("details");
            }

            if (filesStr != null && !filesStr.isBlank()) {
                ArrayNode filesArr = mapper.createArrayNode();
                for (String f : filesStr.split(",")) {
                    String t = f.trim();
                    if (!t.isEmpty())
                        filesArr.add(t);
                }
                if (filesArr.size() > 0)
                    update.set("files", filesArr);
                else
                    update.remove("files");
            } else {
                update.remove("files");
            }

            // timestamp 保持不变，不参与排序（编辑不应改变时序）
            saveThread(threadFile, threadNode);
            rebuildIndex(updateDir);
            return "✅ 已更新记录 #" + (updateIndex + 1);
        } catch (Exception e) {
            return "❌ 编辑失败: " + e.getMessage();
        }
    }
    
    // ===== 内部辅助 =====

    private void saveThread(Path file, ObjectNode node) throws IOException {
        FileOperator.writeAtomic(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
    }

    private void sortUpdates(ArrayNode updates) {
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode n : updates)
            list.add(n);
        // 按 timestamp 倒序排列（最新的在最前）
        list.sort((a, b) -> b.get("timestamp").asText().compareTo(a.get("timestamp").asText()));
        updates.removeAll();
        for (JsonNode n : list)
            updates.add(n);
    }

    // @anchor: updateArchive_rebuildIndex
    // 每次写操作后重建 index.json，确保计数和最后更新时间完全准确
    private void rebuildIndex(Path updateDir) throws IOException {
        Path indexFile = updateDir.resolve("index.json");
        ObjectNode indexNode = mapper.createObjectNode();
        ArrayNode threads = mapper.createArrayNode();

        try (var stream = Files.list(updateDir)) {
            List<Path> threadFiles = stream
                    .filter(p -> p.getFileName().toString().startsWith("thread_") && p.toString().endsWith(".json"))
                    .toList();
            for (Path tf : threadFiles) {
                ObjectNode t = (ObjectNode) mapper.readTree(tf.toFile());
                ObjectNode summary = mapper.createObjectNode();
                summary.put("thread_id", t.get("thread_id").asText());
                summary.put("title", t.get("title").asText());
                summary.put("status", t.get("status").asText());

                ArrayNode ups = (ArrayNode) t.get("updates");
                summary.put("update_count", ups.size());
                if (ups.size() > 0) {
                    summary.put("last_updated", ups.get(0).get("timestamp").asText());
                } else {
                    summary.put("last_updated", "");
                }
                threads.add(summary);
            }
        }
        indexNode.set("threads", threads);
        FileOperator.writeAtomic(indexFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(indexNode));
    }
}