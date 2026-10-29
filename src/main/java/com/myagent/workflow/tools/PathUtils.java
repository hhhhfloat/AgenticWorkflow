// @anchor: pathUtils_intro
// 沙箱路径解析：归一化并强制限制在 ./sandbox 内
package com.myagent.workflow.tools;

import com.myagent.workflow.core.config.AgentConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// @anchor: pathUtils_class
// 沙箱路径工具类：提供安全的相对路径解析，防穿越、防绝对路径、防 Symlink 逃逸
public class PathUtils {
    private static final String sandboxDir = AgentConfig.getSandboxDir();

    // @anchor: pathUtils_safeResolve
    // 将相对路径解析并限制在沙箱根内，拒绝越界
    public static Path safeResolve(String... parts) throws IOException {
        // 1. 获取沙箱根目录的绝对规范化路径
        Path root = Paths.get(sandboxDir).toAbsolutePath().normalize();
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            realRoot = root; // sandbox 还没创建时回退
        }

        // 2. 从根目录开始拼接用户传入的片段
        Path resolved = root;
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                // 拦截绝对路径参数（如 /etc/passwd）
                if (Paths.get(part).isAbsolute()) {
                    throw new IOException("安全拒绝：绝对路径 -> " + part);
                }
                resolved = resolved.resolve(part);
            }
        }

        // 3. 规范化（消除 .. 和 .）
        resolved = resolved.normalize();

        // 4. 核心校验一：逻辑路径边界
        if (!resolved.startsWith(root)) {
            throw new IOException("安全拒绝：路径穿越检测 -> " + String.join("/", parts));
        }

        // 5. 核心校验二：Symlink 逃逸检测
        // 目标文件可能不存在（如新建文件），需要向上寻找最近一个真实存在的父目录
        Path probe = resolved;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe != null) {
            Path realProbe = probe.toRealPath();
            if (!realProbe.startsWith(realRoot)) {
                throw new IOException("安全拒绝：符号链接逃逸检测 -> " + String.join("/", parts));
            }
        }

        return resolved;
    }
}