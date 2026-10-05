// @anchor: mavenProbe_tot_desc
// Maven 探测：PATH → MAVEN_HOME/M2_HOME → IDE 自带与常见安装位置
package com.myagent.workflow.core.config.env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: mavenProbe_class
public class MavenProbe implements Probe {

    @Override
    public Map<String, String> detect() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("mavenCommand", findMaven());
        return r;
    }

    // @anchor: mavenProbe_findMaven
    private String findMaven() {
        String p = ShellPathResolver.which("mvn.cmd", "mvn.bat", "mvn");
        if (p != null) return EnvUtil.normalize(p);

        String mh = System.getenv("MAVEN_HOME");
        if (mh == null) mh = System.getenv("M2_HOME");
        if (mh != null && !mh.isBlank()) {
            for (String exe : new String[]{"mvn.cmd", "mvn.bat", "mvn"}) {
                Path c = Paths.get(mh, "bin", exe);
                if (Files.isRegularFile(c)) return EnvUtil.normalize(c.toString());
            }
        }

        String pf = EnvUtil.envOr("ProgramFiles", "C:\\Program Files");
        String localApp = System.getenv("LOCALAPPDATA");
        String home = EnvUtil.userHome();
        String[] patterns = {
                pf + "\\JetBrains\\*\\plugins\\maven\\lib\\maven3\\bin\\mvn.cmd",
                localApp == null ? null : localApp + "\\JetBrains\\*\\plugins\\maven\\lib\\maven3\\bin\\mvn.cmd",
                home + "\\AppData\\Local\\JetBrains\\*\\plugins\\maven\\lib\\maven3\\bin\\mvn.cmd",
                pf + "\\Eclipse Adoptium\\*\\plugins\\*\\maven*\\bin\\mvn.cmd",
                pf + "\\apache-maven-*\\bin\\mvn.cmd",
                "C:\\apache-maven-*\\bin\\mvn.cmd",
                "C:\\tools\\apache-maven-*\\bin\\mvn.cmd",
                home + "\\scoop\\apps\\maven\\current\\bin\\mvn.cmd",
                home + "\\scoop\\apps\\maven\\*\\bin\\mvn.cmd",
                "C:\\ProgramData\\chocolatey\\bin\\mvn.cmd",
                "C:\\ProgramData\\chocolatey\\lib\\maven\\apache-maven-*\\bin\\mvn.cmd",
        };
        for (String pat : patterns) {
            if (pat == null) continue;
            String f = GlobUtil.latestFile(pat);
            if (f != null) return EnvUtil.normalize(f);
        }
        return "";
    }
}