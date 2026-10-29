// @anchor: gccProbe_intro
// Linux C++ 编译器探测：优先 PATH，其次常见目录，结果写入 mingwCompiler 字段
package com.myagent.workflow.core.config.env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: gccProbe_class
// 探测 g++ / clang++ / c++，复用 mingwCompiler 字段承载 native C++ 编译器路径
public final class GccProbe implements Probe {

    // @anchor: gccProbe_candidateBins
    private static final String[] CANDIDATE_BINS = {
            "/usr/bin", "/usr/local/bin", "/opt/homebrew/bin"
    };

    // @anchor: gccProbe_detect
    @Override
    public Map<String, String> detect() {
        Map<String, String> m = new LinkedHashMap<>();

        String cxx = ShellPathResolver.which("g++", "clang++", "c++");
        if (cxx == null) {
            for (String dir : CANDIDATE_BINS) {
                for (String name : new String[]{"g++", "clang++", "c++"}) {
                    Path p = Paths.get(dir, name);
                    if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                        cxx = p.toString();
                        break;
                    }
                }
                if (cxx != null) break;
            }
        }

        m.put("mingwCompiler", cxx == null ? "" : cxx);
        m.put("msvcCompiler", "");
        m.put("msvcInclude", "");
        m.put("msvcLib", "");
        return m;
    }
}