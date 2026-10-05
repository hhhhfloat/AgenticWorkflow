// @anchor: nodeProbe_intro
// Node.js 探测：PATH → 常见安装位置与 nvm/scoop 目录
package com.myagent.workflow.core.config.env;

import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: nodeProbe_class
public class NodeProbe implements Probe {

    @Override
    public Map<String, String> detect() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("nodeInterpreter", findNode());
        return r;
    }

    // @anchor: nodeProbe_findNode
    private String findNode() {
        String p = ShellPathResolver.which("node.exe", "node");
        if (p != null) return EnvUtil.normalize(p);

        String pf   = EnvUtil.envOr("ProgramFiles", "C:\\Program Files");
        String pf86 = EnvUtil.envOr("ProgramFiles(x86)", "C:\\Program Files (x86)");
        String home = EnvUtil.userHome();
        String localApp = System.getenv("LOCALAPPDATA");

        String[] candidates = {
                pf + "\\nodejs\\node.exe",
                pf86 + "\\nodejs\\node.exe",
                localApp == null ? null : localApp + "\\Programs\\nodejs\\node.exe",
                home + "\\scoop\\apps\\nodejs\\current\\node.exe",
                "C:\\ProgramData\\chocolatey\\bin\\node.exe",
                "C:\\ProgramData\\chocolatey\\lib\\nodejs\\tools\\node.exe",
                home + "\\AppData\\Roaming\\nvm\\*\\node.exe",
                "C:\\nvm4w\\nodejs\\node.exe",
        };
        for (String c : candidates) {
            if (c == null) continue;
            String f = GlobUtil.latestFile(c);
            if (f != null) return EnvUtil.normalize(f);
        }
        return "";
    }
}