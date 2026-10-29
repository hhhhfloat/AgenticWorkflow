// @anchor: shellPathResolver_intro
// PATH 探测：Windows 按 PATHEXT 补后缀，Linux 按 isExecutable 判定
package com.myagent.workflow.core.config.env;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

// @anchor: shellPathResolver_class
public final class ShellPathResolver {
    private ShellPathResolver() {}

    // @anchor: shellPathResolver_which
    public static String which(String... names) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        boolean win = Platform.isWindows();
        String sep = File.pathSeparator;          // ';' Windows / ':' Linux
        String[] exts = win ? extList() : new String[0];

        for (String dir : pathEnv.split(Pattern.quote(sep))) {
            if (dir.isBlank()) continue;
            String dirTrim = dir.trim().replace("\"", "");

            // WSL2 特有：跳过 /mnt/ 挂载，避免 Windows .exe 污染 PATH 解析
            if (!win && dirTrim.startsWith("/mnt/")) continue;

            Path base = Paths.get(dirTrim);
            if (!Files.isDirectory(base)) continue;

            for (String n : names) {
                Path direct = base.resolve(n);
                if (isExecutableFile(direct)) return direct.toString();

                if (win && !n.contains(".")) {
                    for (String ext : exts) {
                        Path c = base.resolve(n + ext);
                        if (Files.isRegularFile(c)) return c.toString();
                    }
                }
            }
        }
        return null;
    }

    // @anchor: shellPathResolver_isExecutableFile
    // Windows 只判 isRegularFile；Linux 额外要求可执行位
    private static boolean isExecutableFile(Path p) {
        if (!Files.isRegularFile(p)) return false;
        return Platform.isWindows() || Files.isExecutable(p);
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