package com.myagent.workflow.tools.runner;

import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.core.config.ConfigEditor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class EncodingSmokeTest {

    public static void main(String[] args) throws Exception {
        Path tmp = Paths.get(AgentConfig.getSandboxDir(), "tmp").toAbsolutePath().normalize();
        Files.createDirectories(tmp);

        // 用例 1：中文正常输出（stdout + stderr）
        Path ok = tmp.resolve("ZhOk.java");
        Files.writeString(ok, """
                public class ZhOk {
                    public static void main(String[] args) {
                        System.out.println("你好，世界！中文输出测试。");
                        System.err.println("这是标准错误的中文输出。");
                    }
                }
                """, StandardCharsets.UTF_8);

        // 用例 2：中文编译错误（类型不兼容）
        Path bad = tmp.resolve("ZhBad.java");
        Files.writeString(bad, """
                public class ZhBad {
                    public static void main(String[] args) {
                        int x = "这行有类型错误";
                    }
                }
                """, StandardCharsets.UTF_8);

        // 用例 3：A7 大输出（500 行代码，编译警告/输出多）
        Path many = tmp.resolve("ManyWarnings.java");
        StringBuilder src = new StringBuilder("public class ManyWarnings {\n");
        src.append("    public static void main(String[] args) {\n");
        for (int i = 0; i < 500; i++) {
            src.append("        @SuppressWarnings(\"unused\") int v").append(i).append(" = ").append(i).append(";\n");
        }
        src.append("        System.out.println(\"done\");\n    }\n}\n");
        Files.writeString(many, src.toString(), StandardCharsets.UTF_8);

        ProcessRunner pr = new ProcessRunner();
        AgentConfig config = ConfigEditor.buildDefault();
        JavaRunner runner = new JavaRunner(config, pr);

        System.out.println("========== 用例 1：中文正常输出 ==========");
        String r1 = runner.compileJava(ok, "tmp/ZhOk.java", true);
        System.out.println(r1);
        System.out.println("含乱码 U+FFFD = " + r1.contains("\uFFFD"));
        System.out.println("含中文'你好' = " + r1.contains("你好"));
        System.out.println("含中文'标准错误' = " + r1.contains("标准错误"));

        System.out.println("\n========== 用例 2：中文编译错误 ==========");
        String r2 = runner.compileJava(bad, "tmp/ZhBad.java", true);
        System.out.println(r2);
        System.out.println("含乱码 U+FFFD = " + r2.contains("\uFFFD"));
        System.out.println("含中文'错误'或'不兼容' = " + (r2.contains("错误") || r2.contains("不兼容")));

        System.out.println("\n========== 用例 3：A7 大输出不误报超时 ==========");
        String r3 = runner.compileJava(many, "tmp/ManyWarnings.java", true);
        System.out.println(r3);
        System.out.println("含超时标记 ⏱️ = " + r3.contains("⏱️"));
        System.out.println("含'运行成功' = " + r3.contains("运行成功"));
    }
}