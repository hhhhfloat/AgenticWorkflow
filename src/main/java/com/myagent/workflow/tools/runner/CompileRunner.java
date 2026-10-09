// @anchor: compileRunner_intro
// 编译运行编排：安全扫描 + 调用 Compiler + 写 .agent_entry.json 注册表
package com.myagent.workflow.tools.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.security.ScanResult;
import com.myagent.workflow.security.SecurityScanner;
import com.myagent.workflow.tools.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: compileRunner_class
// 编译运行器：先安全扫描，再按模式调度 Compiler，成功后写入口注册表
public class CompileRunner {

    private static final Logger logger = LoggerFactory.getLogger(CompileRunner.class);

    private final AgentConfig config;
    private final Compiler compiler;

    // @anchor: compileRunner_constructor
    public CompileRunner(AgentConfig config, Compiler compiler) {
        this.config = config;
        this.compiler = compiler;
    }

    // @anchor: compileRunner_run
    // 编译并运行：先安全扫描，再按模式调度编译器，成功后写入口注册表
    public String run(String filename, String mode, boolean run) {
        try {
            Path filePath = PathUtils.safeResolve(filename);

            if (config.enableSecurityScan()) {
                SecurityScanner scanner = SecurityScanner.getInstance();
                ScanResult scanResult;

                if (Files.isDirectory(filePath)) {
                    scanResult = scanner.scanDirectory(filePath);
                } else if (Files.isRegularFile(filePath)) {
                    scanResult = scanner.scan(filePath);
                } else {
                    return "❌ 路径不存在: " + filename;
                }

                if (!scanResult.passed()) {
                    String report = scanResult.getFormattedReport();
                    logger.warn("安全扫描未通过: {}", filename);
                    return "❌ 安全扫描拦截:\n" + report;
                }
            } else {
                logger.info("⚠\uFE0F 安全扫描已禁用，直接编译: {}", filename);
            }

            String result;

            if ("html".equalsIgnoreCase(mode)) {
                result = compiler.previewHtml(filePath, filename);
            } else if ("java".equalsIgnoreCase(mode)) {
                result = compiler.compileJava(filePath, filename, run);
            } else if ("maven".equalsIgnoreCase(mode)) {
                result = compiler.compileMaven(filePath, run);
            } else if ("cpp".equalsIgnoreCase(mode)) {
                result = compiler.compileAndRunCpp(filePath, filename, run);
            } else if ("python".equalsIgnoreCase(mode)) {
                result = compiler.runPython(filePath, filename, run);
            } else if ("node".equalsIgnoreCase(mode)) {
                result = compiler.runNode(filePath, filename, run);
            } else {
                result = compiler.compileAuto(filePath, filename, run);
            }

            if (!isErrorResult(result)) {
                Path projectDir = filePath.getParent();
                if (Files.isDirectory(filePath)) {
                    projectDir = filePath;
                }
                Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();

                if (projectDir != null && projectDir.startsWith(sandboxRoot)) {
                    writeEntryFile(projectDir, filename, mode);
                } else {
                    logger.warn("⚠️ 路径检查未通过，跳过写入");
                }
            }

            return result;

        } catch (IOException e) {
            logger.error("编译运行异常", e);
            return "编译运行异常: " + e.getMessage();
        }
    }

    // @anchor: compileRunner_isError
    // 判断编译/运行结果字符串是否表示失败
    private boolean isErrorResult(String result) {
        if (result == null) return true;
        return result.startsWith("❌") ||
                result.contains("失败") ||
                result.contains("超时") ||
                result.contains("未找到") ||
                result.contains("不存在") ||
                result.contains("exception");
    }

    // @anchor: compileRunner_writeEntry
    // 把最近一次成功运行的入口信息写入 .agent_entry.json
    private void writeEntryFile(Path projectDir, String filename, String mode) {
        try {
            // 目标必须真实存在，避免为悬空文件写注册表
            Path target;
            try {
                target = PathUtils.safeResolve(filename);
            } catch (IOException e) {
                logger.warn("⚠️ 目标路径不安全，跳过注册表写入: {}", filename);
                return;
            }
            if (!Files.exists(target)) {
                logger.warn("⚠️ 目标文件不存在，跳过注册表写入: {}", filename);
                return;
            }
            // 统一存为相对沙箱根的路径，避免 sandbox/ 前缀导致 /runProject 解析错误
            Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
            String relPath = sandboxRoot.relativize(target.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');

            Path entryFile = projectDir.resolve(".agent_entry.json");
            logger.info("📝 正在写入注册表: " + entryFile);

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("filename", relPath);
            meta.put("mode", mode);
            String json = new ObjectMapper().writeValueAsString(meta);
            Files.writeString(entryFile, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("✅ 注册表写入成功");
        } catch (IOException e) {
            logger.warn("❌ 注册表写入失败: {}", e.getMessage());
        }
    }
}