// @anchor: shellPathResolver_tot_desc
// PATH 探测：按 PATHEXT 依次尝试可执行文件后缀
package com.myagent.workflow.core.config.env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;

// @anchor: shellPathResolver_class
public final class ShellPathResolver {
    private ShellPathResolver() {}

    // @anchor: shellPathResolver_which
    public static String which(String... names) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] exts = extList();

        for (String dir : pathEnv.split(";")) {
            if (dir.isBlank()) continue;
            String dirTrim = dir.trim().replace("\"", "");
            Path base = Paths.get(dirTrim);
            if (!Files.isDirectory(base)) continue;

            for (String n : names) {
                Path direct = base.resolve(n);
                if (Files.isRegularFile(direct)) return direct.toString();
                if (!n.contains(".")) {
                    for (String ext : exts) {
                        Path c = base.resolve(n + ext);
                        if (Files.isRegularFile(c)) return c.toString();
                    }
                }
            }
        }
        return null;
    }

    // @anchor: shellPathResolver_extList
    private static String[] extList() {
        String ext = System.getenv("PATHEXT");
        if (ext == null || ext.isBlank()) return new String[]{".exe", ".cmd", ".bat"};
        return Arrays.stream(ext.split(";"))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}