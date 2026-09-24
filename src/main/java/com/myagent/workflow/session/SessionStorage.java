package com.myagent.workflow.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.core.ContextManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 会话磁盘存储 —— 负责 Session 的持久化与加载。
 * <p>
 * 目录结构：
 * ./sessions/
 *     ├── index.json                      ← 所有会话的元数据索引
 *     ├── {sessionId}/
 *     │   ├── meta.json                   ← 会话元数据
 *     │   ├── immutable_base.jsonl        ← 不可变基础区，每行一条消息
 *     │   └── volatile_working.jsonl      ← 易失工作区，每行一条消息
 *     └── ...
 * <p>
 * 设计原则：
 * - 不做并发控制（由 SessionManager 保证）
 * - 不做状态判断（由调用方保证）
 * - 只负责"存"和"取"两件事
 */
public class SessionStorage {

    private static final Path SESSIONS_DIR = Paths.get("./sessions");
    private static final String INDEX_FILE = "index.json";
    private static final String META_FILE = "meta.json";
    private static final String BASE_FILE = "immutable_base.jsonl";
    private static final String WORKING_FILE = "volatile_working.jsonl";

    private final ObjectMapper objectMapper;
    private final ObjectMapper jsonlMapper;

    public SessionStorage() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.jsonlMapper = new ObjectMapper();
        ensureSessionsDir();
    }

    private void ensureSessionsDir() {
        try {
            if (!Files.exists(SESSIONS_DIR)) {
                Files.createDirectories(SESSIONS_DIR);
            }
        } catch (IOException e) {
            throw new RuntimeException("无法创建 sessions 目录: " + SESSIONS_DIR, e);
        }
    }

    // ==================== 保存 ====================

    /**
     * 保存单个会话到磁盘。
     * <p>
     * - meta.json：覆盖写
     * - immutable_base.jsonl：覆盖写（内容来自 ContextManager 快照）
     * - volatile_working.jsonl：覆盖写
     * - index.json：增量更新
     */
    public void save(Session session) throws IOException {
        Path sessionDir = SESSIONS_DIR.resolve(session.getSessionId());
        Files.createDirectories(sessionDir);

        // 1. 写 meta.json
        writeJson(sessionDir.resolve(META_FILE), session.getMeta());

        // 2. 写 immutable_base.jsonl
        if (session.getContextManager() != null) {
            writeJsonl(sessionDir.resolve(BASE_FILE),
                    session.getContextManager().getImmutableBaseSnapshot());
            writeJsonl(sessionDir.resolve(WORKING_FILE),
                    session.getContextManager().getVolatileWorkingSnapshot());
        }

        // 3. 更新索引
        updateIndex(session.getMeta());
    }

    // ==================== 加载 ====================

    /**
     * 从磁盘加载一个会话。
     * 返回的 Session 已挂载 ContextManager 并恢复了上下文快照。
     */
    public Session load(String sessionId, AgentConfig config) throws IOException {
        Path sessionDir = SESSIONS_DIR.resolve(sessionId);
        if (!Files.exists(sessionDir)) {
            throw new IOException("会话不存在: " + sessionId);
        }

        // 1. 读 meta
        SessionMeta meta = readJson(sessionDir.resolve(META_FILE), SessionMeta.class);

        // 2. 创建 Session 骨架（注意：构造时会生成新的 sessionId，需要恢复）
        Session session = new Session(meta.sessionId() ,config);
        session.restoreMeta(meta);

        // 3. 创建 ContextManager 并挂载
        ContextManager cm = new ContextManager(
                new ObjectMapper(),
                session::log
        );
        session.attachContextManager(cm);

        // 4. 恢复上下文快照
        List<Map<String, Object>> base = readJsonl(sessionDir.resolve(BASE_FILE));
        cm.restoreImmutableBase(base);

        Path workingFile = sessionDir.resolve(WORKING_FILE);
        if (Files.exists(workingFile)) {
            List<Map<String, Object>> working = readJsonl(workingFile);
            cm.restoreVolatileWorking(working);
        }

        return session;
    }

    // ==================== 列表 ====================

    /**
     * 读取所有会话的元数据，按 lastActiveAt 倒序排列。
     */
    public List<SessionMeta> listAll() {
        Path indexFile = SESSIONS_DIR.resolve(INDEX_FILE);
        if (!Files.exists(indexFile)) {
            return List.of();
        }
        try {
            IndexRoot root = readJson(indexFile, IndexRoot.class);
            if (root == null || root.sessions == null) {
                return List.of();
            }
            return root.sessions.stream()
                    .sorted(Comparator.comparing(SessionMeta::lastActiveAt).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("⚠️ 读取 index.json 失败: " + e.getMessage());
            return List.of();
        }
    }

    // ==================== 删除 ====================

    public void delete(String sessionId) throws IOException {
        Path sessionDir = SESSIONS_DIR.resolve(sessionId);
        if (Files.exists(sessionDir)) {
            try (Stream<Path> paths = Files.walk(sessionDir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
            }
        }
        removeFromIndex(sessionId);
    }

    // ==================== 内部：索引维护 ====================

    private synchronized void updateIndex(SessionMeta meta) throws IOException {
        Path indexFile = SESSIONS_DIR.resolve(INDEX_FILE);
        IndexRoot root = Files.exists(indexFile) ? readJson(indexFile, IndexRoot.class) : new IndexRoot();

        if (root.sessions == null) root.sessions = new ArrayList<>();

        // 移除旧的（如果有）
        root.sessions.removeIf(m -> m.sessionId().equals(meta.sessionId()));
        // 加入新的
        root.sessions.add(meta);

        writeJson(indexFile, root);
    }

    private synchronized void removeFromIndex(String sessionId) throws IOException {
        Path indexFile = SESSIONS_DIR.resolve(INDEX_FILE);
        if (!Files.exists(indexFile)) return;

        IndexRoot root = readJson(indexFile, IndexRoot.class);
        if (root == null || root.sessions == null) return;

        root.sessions.removeIf(m -> m.sessionId().equals(sessionId));
        writeJson(indexFile, root);
    }

    // ==================== 内部：JSON 读写 ====================

    private void writeJson(Path file, Object obj) throws IOException {
        String json = objectMapper.writeValueAsString(obj);
        Files.writeString(file, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private <T> T readJson(Path file, Class<T> clazz) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return objectMapper.readValue(json, clazz);
    }

    private void writeJsonl(Path file, List<Map<String, Object>> messages) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            sb.append(jsonlMapper.writeValueAsString(msg)).append(System.lineSeparator());  // ← jsonlMapper
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private List<Map<String, Object>> readJsonl(Path file) throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = jsonlMapper.readValue(line, Map.class);  // ← jsonlMapper
            result.add(msg);
        }
        return result;
    }

    // ==================== 内部类：索引结构 ====================

    /**
     * index.json 的顶层结构。
     * 用 public 字段是为了让 Jackson 直接反序列化。
     */
    public static class IndexRoot {
        public List<SessionMeta> sessions = new ArrayList<>();
    }
}