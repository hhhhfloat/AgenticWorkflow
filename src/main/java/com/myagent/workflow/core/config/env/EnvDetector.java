// @anchor: envDetector_intro
// 本机开发环境探测器门面：遍历 Probe 收集工具链路径并生成配置文件
package com.myagent.workflow.core.config.env;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// @anchor: envDetector_class
// 环境探测器：按顺序遍历各 Probe，组装 Map 并写出 properties
public final class EnvDetector {

    public static final String CONFIG_FILE_NAME = "agent-config.properties";

    // @anchor: envDetector_probes
    // 探测顺序：与旧实现的 Map 写入顺序保持一致
    private static final List<Probe> PROBES = List.of(
            new JavaHomeProbe(),
            new MavenProbe(),
            new PythonProbe(),
            new NodeProbe(),
            new MinGwProbe(),
            new MsvcProbe()
    );

    private EnvDetector() {}

    // @anchor: envDetector_main
    public static void main(String[] args) {
        Path out = Paths.get(args.length > 0 ? args[0] : CONFIG_FILE_NAME);
        try {
            Path written = detectAndWrite(out);
            System.out.println();
            System.out.println("✅ 已生成配置：" + written.toAbsolutePath());
            System.out.println("   可按需编辑该文件后重新启动。");
        } catch (Exception e) {
            System.err.println("❌ 环境探测失败：" + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // @anchor: envDetector_detectAndWrite
    public static Path detectAndWrite(Path out) throws IOException {
        System.out.println("🔍 正在探测本机开发环境 ...");
        long t0 = System.currentTimeMillis();
        Map<String, String> env = collect();
        ConfigRenderer.printSummary(env, System.currentTimeMillis() - t0);

        String text = ConfigRenderer.render(env);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, text,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return out;
    }

    // @anchor: envDetector_collect
    // 汇总各类工具链探测结果，并决策默认 C++ 编译器类型
    public static Map<String, String> collect() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Probe probe : PROBES) {
            m.putAll(probe.detect());
        }

        String mingw = m.getOrDefault("mingwCompiler", "");
        String msvc  = m.getOrDefault("msvcCompiler", "");
        String cppType;
        if (!mingw.isEmpty())     cppType = "mingw";
        else if (!msvc.isEmpty()) cppType = "msvc";
        else                      cppType = "mingw";
        m.put("cppCompilerType", cppType);
        return m;
    }
}