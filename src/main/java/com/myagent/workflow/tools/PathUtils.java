package com.myagent.workflow.tools;


import com.myagent.workflow.core.AgentConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathUtils {
    private static final String sandboxDir = AgentConfig.getSandboxDir();
    static Path safeResolve(String... parts) throws IOException {
        // 1. 获取沙箱根目录的绝对规范化路径
        Path root = Paths.get(sandboxDir).toAbsolutePath().normalize();

        // 2. 从根目录开始拼接用户传入的片段
        Path resolved = root;
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                resolved = resolved.resolve(part);
            }
        }

        // 3. 规范化（消除 .. 和 .）
        resolved = resolved.normalize();

        // 4. 核心校验：确保最终路径仍然以沙箱根目录开头
        if (!resolved.startsWith(root)) {
            throw new IOException("安全拒绝：路径穿越检测 -> " + String.join("/", parts));
        }

        return resolved;
    }
}
