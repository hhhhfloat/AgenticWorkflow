// @anchor: anchorIndexCache_intro
// 锚点索引缓存：按文件 mtime 失效的只读缓存，供 AnchorIndex / AnchorQuery / AnchorFormatter 共用
package com.myagent.workflow.tools.anchor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// @anchor: anchorIndexCache_class
// 索引缓存：.anchors.json / .project_index.json 按 mtime 失效
class AnchorIndexCache {

    private final ObjectMapper objectMapper;
    private final Map<Path, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(long mtime, Map<String, List<Map<String, Object>>> data) {}

    AnchorIndexCache(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // @anchor: anchorIndexCache_load
    // 读取索引文件；命中缓存（mtime 相同）时直接返回。返回的 Map 视为只读，调用方要修改必须先复制
    Map<String, List<Map<String, Object>>> load(Path file) throws IOException {
        if (!Files.exists(file)) return Map.of();
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return Map.of();
        }
        Entry cached = cache.get(file);
        if (cached != null && cached.mtime() == mtime) {
            return cached.data();
        }
        String content = Files.readString(file);
        Map<String, List<Map<String, Object>>> data =
                objectMapper.readValue(content, new TypeReference<>() {});
        cache.put(file, new Entry(mtime, data));
        return data;
    }

    // @anchor: anchorIndexCache_invalidate
    // 写路径调用：丢弃指定文件的缓存
    void invalidate(Path file) {
        cache.remove(file);
    }
}