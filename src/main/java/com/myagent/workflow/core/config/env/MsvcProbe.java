// @anchor: msvcProbe_intro
// MSVC 探测：vswhere 或常见安装目录 → cl.exe + include + lib
package com.myagent.workflow.core.config.env;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// @anchor: msvcProbe_class
public class MsvcProbe implements Probe {

    @Override
    public Map<String, String> detect() {
        MsvcPaths p = findMsvc();
        Map<String, String> r = new LinkedHashMap<>();
        r.put("msvcCompiler", p.compiler());
        r.put("msvcInclude",  p.include());
        r.put("msvcLib",      p.lib());
        return r;
    }

    // @anchor: msvcProbe_msvcPaths
    private record MsvcPaths(String compiler, String include, String lib) {}

    // @anchor: msvcProbe_findMsvc
    private MsvcPaths findMsvc() {
        String vsRoot = findVsRoot();
        if (vsRoot == null) return new MsvcPaths("", "", "");

        String msvc = GlobUtil.latestDir(vsRoot + "\\VC\\Tools\\MSVC\\*");
        if (msvc == null) return new MsvcPaths("", "", "");

        String cl = GlobUtil.firstFile(
                msvc + "\\bin\\Hostx86\\x86\\cl.exe",
                msvc + "\\bin\\Hostx64\\x64\\cl.exe");
        if (cl == null) cl = GlobUtil.latestFile(msvc + "\\bin\\Host*\\*\\cl.exe");

        List<String> inc = new ArrayList<>();
        List<String> lib = new ArrayList<>();
        EnvUtil.addDirIfExists(inc, msvc + "\\include");
        EnvUtil.addDirIfExists(inc, msvc + "\\ATLMFC\\include");
        EnvUtil.addDirIfExists(inc, vsRoot + "\\VC\\Auxiliary\\VS\\include");
        EnvUtil.addDirIfExists(lib, msvc + "\\ATLMFC\\lib\\x86");
        EnvUtil.addDirIfExists(lib, msvc + "\\lib\\x86");

        for (String sdkBase : new String[]{
                "C:\\Program Files (x86)\\Windows Kits\\10",
                "C:\\Program Files\\Windows Kits\\10"}) {
            if (!Files.isDirectory(Paths.get(sdkBase))) continue;

            String sdkInc = GlobUtil.latestDir(sdkBase + "\\Include\\*");
            String sdkLib = GlobUtil.latestDir(sdkBase + "\\Lib\\*");
            if (sdkInc != null) {
                for (String sub : new String[]{"ucrt", "um", "shared", "winrt", "cppwinrt"})
                    EnvUtil.addDirIfExists(inc, sdkInc + "\\" + sub);
            }
            if (sdkLib != null) {
                String netfx = GlobUtil.latestDir(sdkBase + "\\NETFXSDK\\*\\lib\\um\\x86");
                if (netfx != null) EnvUtil.addDirIfExists(lib, netfx);
                EnvUtil.addDirIfExists(lib, sdkLib + "\\ucrt\\x86");
                EnvUtil.addDirIfExists(lib, sdkLib + "\\um\\x86");
            }
            String netfxInc = GlobUtil.latestDir(sdkBase + "\\NETFXSDK\\*\\include\\um");
            if (netfxInc != null) EnvUtil.addDirIfExists(inc, netfxInc);
            break;
        }

        return new MsvcPaths(
                EnvUtil.normalize(cl),
                EnvUtil.normalize(String.join(";", inc)),
                EnvUtil.normalize(String.join(";", lib)));
    }

    // @anchor: msvcProbe_findVsRoot
    // 用 vswhere 或常见安装目录定位 Visual Studio 根目录；vswhere 带 10 秒超时
    private String findVsRoot() {
        String vswhere = "C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe";
        if (Files.isRegularFile(Paths.get(vswhere))) {
            try {
                String out = CommandRunner.run(10, vswhere,
                        "-latest", "-products", "*",
                        "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                        "-property", "installationPath").trim();
                if (!out.isEmpty() && Files.isDirectory(Paths.get(out))) return out;
            } catch (Exception ignored) {}
        }
        for (String base : new String[]{
                "C:\\Program Files\\Microsoft Visual Studio",
                "C:\\Program Files (x86)\\Microsoft Visual Studio"}) {
            String cand = GlobUtil.latestDir(base + "\\*\\*\\*");
            if (cand != null && Files.isDirectory(Paths.get(cand, "VC", "Tools", "MSVC")))
                return cand;
        }
        return null;
    }
}