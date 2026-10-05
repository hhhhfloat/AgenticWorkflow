// @anchor: pythonProbe_tot_desc
// Python 探测：PATH → py launcher → 常见安装位置
package com.myagent.workflow.core.config.env;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: pythonProbe_class
public class PythonProbe implements Probe {

    @Override
    public Map<String, String> detect() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("pythonInterpreter", findPython());
        return r;
    }

    // @anchor: pythonProbe_findPython
    private String findPython() {
        String p = ShellPathResolver.which("python.exe", "python3.exe", "python", "python3");
        if (p != null) return EnvUtil.normalize(p);

        String viaPy = pythonViaPyLauncher();
        if (viaPy != null) return viaPy;

        String home = EnvUtil.userHome();
        String localApp = System.getenv("LOCALAPPDATA");
        String programData = System.getenv("ProgramData");
        String[] patterns = {
                localApp == null ? null : localApp + "\\Programs\\Python\\Python*\\python.exe",
                localApp == null ? null : localApp + "\\Python\\bin\\python.exe",
                localApp == null ? null : localApp + "\\Microsoft\\WindowsApps\\python.exe",
                home + "\\AppData\\Local\\Programs\\Python\\Python*\\python.exe",
                home + "\\scoop\\apps\\python\\current\\python.exe",
                home + "\\scoop\\apps\\python*\\current\\python.exe",
                "C:\\Python*\\python.exe",
                "C:\\Program Files\\Python*\\python.exe",
                programData == null ? null : programData + "\\Anaconda3\\python.exe",
                home + "\\Anaconda3\\python.exe",
                home + "\\miniconda3\\python.exe",
        };
        for (String pat : patterns) {
            if (pat == null) continue;
            String f = GlobUtil.latestFile(pat);
            if (f != null) return EnvUtil.normalize(f);
        }
        return "";
    }

    // @anchor: pythonProbe_pythonViaPyLauncher
    // 通过 Windows py launcher 查询真实解释器路径；带 5 秒超时
    private String pythonViaPyLauncher() {
        String py = ShellPathResolver.which("py.exe", "py");
        if (py == null) return null;
        try {
            String out = CommandRunner.run(5, py, "-c", "import sys; print(sys.executable)").trim();
            if (!out.isEmpty() && Files.isRegularFile(Paths.get(out))) return EnvUtil.normalize(out);
        } catch (Exception ignored) {}
        return null;
    }
}