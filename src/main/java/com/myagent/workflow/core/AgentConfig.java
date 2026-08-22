package com.myagent.workflow.core;

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
        boolean enableSecurityScan
) {
    // ===== 系统级常量（静态） =====
    private static final String ARCHIVE_VERSION = "v4_0";
    private static final String SANDBOX_DIR = "./sandbox";
    private static final String API_URL = "https://api.deepseek.com/chat/completions";

    // ===== 🚀 新增：模型常量（替代硬编码） =====
    private static final String MODEL_FLASH = "deepseek-v4-flash";
    private static final String MODEL_PRO = "deepseek-v4-pro";

    // ===== 🚀 新增：锯齿压缩策略阈值 =====
    private static final int CHECKPOINT_MIN_INTERVAL = 5;
    private static final int CHECKPOINT_MAX_INTERVAL = 15;

    // ===== 🚀 锚点索引文件名（项目内部） =====
    private static final String ANCHOR_INDEX_NAME = ".anchors.json";

    private static final boolean ENABLE_SECURITY_SCAN = true;
    private static final boolean IS_AUTO_OPEN_BROWSERS = false;
    private static final int DEFAULT_MAX_ITERATIONS = 30;

    // ===== 原有的静态配置 =====
    private static final String MAVEN_COMMAND =
            "I:/IntelliJ IDEA 2025.3.3/plugins/maven/lib/maven3/bin/mvn.cmd";
    private static final String JAVA_HOME = "C:/Program Files/Java/jdk-21.0.10";
    private static final String CPP_COMPILER_TYPE = "mingw";
    private static final String MINGW_COMPILER = "C:/MinGW/bin/g++.exe";
    private static final String MSVC_COMPILER = "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/bin/Hostx86/x86/cl.exe";
    private static final String MSVC_INCLUDE =
            "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/include;" +
                    "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/ATLMFC/include;" +
                    "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Auxiliary/VS/include;" +
                    "C:/Program Files (x86)/Windows Kits/10/include/10.0.26100.0/ucrt;" +
                    "C:/Program Files (x86)/Windows Kits/10/include/10.0.26100.0/um;" +
                    "C:/Program Files (x86)/Windows Kits/10/include/10.0.26100.0/shared;" +
                    "C:/Program Files (x86)/Windows Kits/10/include/10.0.26100.0/winrt;" +
                    "C:/Program Files (x86)/Windows Kits/10/include/10.0.26100.0/cppwinrt;" +
                    "C:/Program Files (x86)/Windows Kits/NETFXSDK/4.8/include/um";
    private static final String MSVC_LIB =
            "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/ATLMFC/lib/x86;" +
                    "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/lib/x86;" +
                    "C:/Program Files (x86)/Windows Kits/NETFXSDK/4.8/lib/um/x86;" +
                    "C:/Program Files (x86)/Windows Kits/10/lib/10.0.26100.0/ucrt/x86;" +
                    "C:/Program Files (x86)/Windows Kits/10/lib/10.0.26100.0/um/x86";
    private static final String PYTHON_INTERPRETER = "C:/Users/hhhhu/AppData/Local/Python/bin/python.exe";
    private static final String NODE_INTERPRETER = "C:/Program Files/nodejs/node.exe";

    // ========== 公共 Getter ==========

    public static String getModelFlash() { return MODEL_FLASH; }
    public static String getModelPro() { return MODEL_PRO; }
    public static int getCheckpointMinInterval() { return CHECKPOINT_MIN_INTERVAL; }
    public static int getCheckpointMaxInterval() { return CHECKPOINT_MAX_INTERVAL; }
    public static int getDefaultMaxIterations() { return DEFAULT_MAX_ITERATIONS; }
    public static String getSandboxDir() { return SANDBOX_DIR; }
    public static String getApiUrl() { return API_URL; }
    public static String getArchiveVersion() { return ARCHIVE_VERSION; }

    // 🚀 新增：获取锚点索引文件名
    public static String getAnchorIndexName() { return ANCHOR_INDEX_NAME; }

    // ========== 工厂方法 ==========

    public static AgentConfig buildDefaultConfig() {
        return new AgentConfig(
                System.getenv("DEEPSEEK_API_KEY"),
                MODEL_FLASH,
                IS_AUTO_OPEN_BROWSERS,
                MAVEN_COMMAND,
                JAVA_HOME,
                PYTHON_INTERPRETER,
                NODE_INTERPRETER,
                CPP_COMPILER_TYPE,
                MSVC_COMPILER,
                MSVC_INCLUDE,
                MSVC_LIB,
                MINGW_COMPILER,
                ENABLE_SECURITY_SCAN
        );
    }
}