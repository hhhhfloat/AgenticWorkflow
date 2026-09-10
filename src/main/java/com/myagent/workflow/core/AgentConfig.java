package com.myagent.workflow.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public record AgentConfig(
        // ===== 用户可配置字段（实例） =====
        String apiKey,
        String model,
        boolean autoOpenBrowser,
        String mavenCommand,
        String javaHome,
        String pythonInterpreter,
        String nodeInterpreter,
        String cppCompilerType,
        String msvcCompiler,
        String msvcInclude,
        String msvcLib,
        String mingwCompiler,
        boolean enableSecurityScan,
        boolean enableCompression,
        int checkpointMinInterval,
        int checkpointMaxInterval
) {
    // ===== 系统级常量（与机器无关，不需要外部化） =====
    private static final String ARCHIVE_VERSION = "v4_2";
    private static final String SANDBOX_DIR     = "./sandbox";
    private static final String API_URL         = "https://api.deepseek.com/chat/completions";
    private static final String MODEL_FLASH     = "deepseek-v4-flash";
    private static final String MODEL_PRO       = "deepseek-v4-pro";
    private static final String ANCHOR_INDEX_NAME = ".anchors.json";

    // ===== 配置文件定位 =====
    private static final String CONFIG_FILE_NAME = "agent-config.properties";
    private static final String CONFIG_ENV_KEY   = "AGENT_CONFIG"; // 环境变量可覆盖路径

    // ===== 默认值（配置文件缺失时兜底；机器相关路径一律留空） =====
    private static final String  DEFAULT_MODEL               = MODEL_FLASH;
    private static final boolean DEFAULT_AUTO_OPEN_BROWSER   = false;
    private static final String  DEFAULT_CPP_TYPE            = "mingw";
    private static final boolean DEFAULT_SECURITY_SCAN       = true;
    private static final boolean DEFAULT_COMPRESSION         = true;
    private static final int     DEFAULT_MIN_COMPRESS        = 5;
    private static final int     DEFAULT_MAX_COMPRESS        = 15;
    private static final int     DEFAULT_MAX_ITERATIONS      = 30;

    // ========== 公共静态 Getter ==========

    public static String getModelFlash()          { return MODEL_FLASH; }
    public static String getModelPro()            { return MODEL_PRO; }
    public static int    getDefaultMaxIterations(){ return DEFAULT_MAX_ITERATIONS; }
    public static String getSandboxDir()          { return SANDBOX_DIR; }
    public static String getApiUrl()              { return API_URL; }
    public static String getArchiveVersion()      { return ARCHIVE_VERSION; }
    public static String getAnchorIndexName()     { return ANCHOR_INDEX_NAME; }

    // ========== 工厂方法 ==========

    public static AgentConfig buildDefaultConfig() {
        Properties p = loadProperties();

        return new AgentConfig(
                // API Key：环境变量优先，其次配置文件
                firstNonEmpty(System.getenv("DEEPSEEK_API_KEY"),
                        p.getProperty("agent.apiKey")),

                getString(p, "agent.model", DEFAULT_MODEL),
                getBool  (p, "agent.autoOpenBrowser",   DEFAULT_AUTO_OPEN_BROWSER),

                getString(p, "env.mavenCommand",        ""),
                getString(p, "env.javaHome",            ""),
                getString(p, "env.pythonInterpreter",   ""),
                getString(p, "env.nodeInterpreter",     ""),
                getString(p, "env.cppCompilerType",     DEFAULT_CPP_TYPE),
                getString(p, "env.msvcCompiler",        ""),
                getString(p, "env.msvcInclude",         ""),
                getString(p, "env.msvcLib",             ""),
                getString(p, "env.mingwCompiler",       ""),

                getBool  (p, "agent.enableSecurityScan",    DEFAULT_SECURITY_SCAN),
                getBool  (p, "agent.enableCompression",     DEFAULT_COMPRESSION),
                getInt   (p, "agent.checkpointMinInterval", DEFAULT_MIN_COMPRESS),
                getInt   (p, "agent.checkpointMaxInterval", DEFAULT_MAX_COMPRESS)
        );
    }

    // ========== 配置加载 ==========

    /**
     * 查找顺序：
     *   1) 环境变量 AGENT_CONFIG 指向的文件
     *   2) 工作目录 ./agent-config.properties
     *   3) classpath 根 /agent-config.properties
     *   4) 都没有 → 返回空 Properties，用内置默认值
     */
    private static Properties loadProperties() {
        Properties props = new Properties();

        // 1) 环境变量
        String envPath = System.getenv(CONFIG_ENV_KEY);
        if (envPath != null && !envPath.isBlank()) {
            Path path = Paths.get(envPath);
            if (Files.isRegularFile(path)) {
                if (tryLoad(props, path)) return props;
            } else {
                System.err.println("[AgentConfig] AGENT_CONFIG 指向的文件不存在：" + path);
            }
        }

        // 2) 工作目录
        Path cwd = Paths.get(CONFIG_FILE_NAME);
        if (Files.isRegularFile(cwd)) {
            if (tryLoad(props, cwd)) return props;
        }

        // 3) classpath
        try (InputStream in = AgentConfig.class.getResourceAsStream("/" + CONFIG_FILE_NAME)) {
            if (in != null) {
                props.load(in);
                System.out.println("[AgentConfig] 已从 classpath 加载 " + CONFIG_FILE_NAME);
                return props;
            }
        } catch (IOException e) {
            System.err.println("[AgentConfig] classpath 配置读取失败：" + e.getMessage());
        }

        System.out.println("[AgentConfig] 未找到 " + CONFIG_FILE_NAME + "，尝试自动探测生成……");

        try {
            Path generated = EnvDetector.detectAndWrite(Paths.get(CONFIG_FILE_NAME));
            System.out.println("[AgentConfig] ✅ 已生成 " + generated.toAbsolutePath());
            if (tryLoad(props, generated)) return props;
        } catch (Exception e) {
            System.err.println("[AgentConfig] 自动探测失败：" + e.getMessage());
        }
        System.out.println("[AgentConfig] ⚠️ 使用内置默认值启动");
        return props;
    }

    private static boolean tryLoad(Properties props, Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            System.out.println("[AgentConfig] 已加载配置：" + path.toAbsolutePath());
            return true;
        } catch (IOException e) {
            System.err.println("[AgentConfig] 读取失败 " + path + " -> " + e.getMessage());
            return false;
        }
    }

    // ========== 取值辅助 ==========

    private static String getString(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static boolean getBool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return (v == null || v.isBlank()) ? def : Boolean.parseBoolean(v.trim());
    }

    private static int getInt(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[AgentConfig] " + key + " 不是合法整数：'" + v + "'，用默认 " + def);
            return def;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}