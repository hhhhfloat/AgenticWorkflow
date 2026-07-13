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
        String mingwCompiler
) {
    // ===== 系统级常量（静态） =====
    private static final String ARCHIVE_VERSION = "v3_0";
    private static final String SANDBOX_DIR = "./sandbox";
    private static final String ANCHOR_INDEX_FILE = "./sandbox/.anchor_index.json";
    private static final String API_URL = "https://api.deepseek.com/chat/completions";

    private static final boolean IS_AUTO_OPEN_BROWSERS = false;

    private static final String MODEL_NAME = "deepseek-v4-pro";

    private static final int DEFAULT_MAX_ITERATIONS = 30;

    public static int getDefaultMaxIterations() {
        return DEFAULT_MAX_ITERATIONS;
    }

    private static final String MAVEN_COMMAND =
            "I:/IntelliJ IDEA 2025.3.3/plugins/maven/lib/maven3/bin/mvn.cmd";

    private static final String JAVA_HOME = "C:/Program Files/Java/jdk-21.0.10";

    // @anchor: agentConfig_cpp

    /** C++ 编译器类型：'msvc' 或 'mingw' */
    private static final String CPP_COMPILER_TYPE = "mingw";  // 或 "msvc"

    /** MinGW C++ 编译器路径 */
    private static final String MINGW_COMPILER = "C:/MinGW/bin/g++.exe";


    /** MSVC C++ 编译器路径（Visual Studio 自带的 cl.exe） */
    private static final String MSVC_COMPILER = "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/bin/Hostx86/x86/cl.exe";

    /** MSVC 头文件搜索路径（INCLUDE 环境变量） */
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

    /** MSVC 库文件搜索路径（LIB 环境变量） */
    private static final String MSVC_LIB =
            "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/ATLMFC/lib/x86;" +
                    "C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.44.35207/lib/x86;" +
                    "C:/Program Files (x86)/Windows Kits/NETFXSDK/4.8/lib/um/x86;" +
                    "C:/Program Files (x86)/Windows Kits/10/lib/10.0.26100.0/ucrt/x86;" +
                    "C:/Program Files (x86)/Windows Kits/10/lib/10.0.26100.0/um/x86";

    // @anchor: agentConfig_python
    /** Python 解释器路径（如果已添加到 PATH，直接用 "python"） */
    private static final String PYTHON_INTERPRETER = "C:/Users/hhhhu/AppData/Local/Python/bin/python.exe";

    // @anchor: agentConfig_node
    /** Node.js 解释器路径（如果已添加到 PATH，直接用 "node"） */
    private static final String NODE_INTERPRETER = "C:/Program Files/nodejs/node.exe";

    public static AgentConfig buildDefaultConfig(){
        return new AgentConfig(
                System.getenv("DEEPSEEK_API_KEY"),
                MODEL_NAME,
                IS_AUTO_OPEN_BROWSERS,
                MAVEN_COMMAND,
                JAVA_HOME,
                PYTHON_INTERPRETER,
                NODE_INTERPRETER,
                CPP_COMPILER_TYPE,
                MSVC_COMPILER,
                MSVC_INCLUDE,
                MSVC_LIB,
                MINGW_COMPILER
        );
    }

    public static String getSandboxDir() {
        return SANDBOX_DIR;
    }

    public static String getAnchorIndexFile() {
        return ANCHOR_INDEX_FILE;
    }

    public static String getApiUrl() {
        return API_URL;
    }

    public static String getArchiveVersion() {
        return ARCHIVE_VERSION;
    }

}