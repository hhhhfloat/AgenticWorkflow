// @anchor: htmlPreviewer_intro
// HTML 预览器：输出可访问的沙箱 URL，可选自动打开浏览器
package com.myagent.workflow.tools.runner;

import com.myagent.workflow.core.config.AgentConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// @anchor: htmlPreviewer_class
// HTML 预览器：仅生成预览 URL，不执行
public class HtmlPreviewer {

    private final boolean autoOpenBrowser;

    // @anchor: htmlPreviewer_constructor
    public HtmlPreviewer(AgentConfig config) {
        this.autoOpenBrowser = config.autoOpenBrowser();
    }

    // @anchor: htmlPreviewer_previewHtml
    // HTML 预览：输出预览地址，可选自动打开浏览器
    public String previewHtml(Path filePath, String filename) throws IOException {
        if (!Files.exists(filePath)) {
            return "HTML 文件不存在: " + filename;
        }

        String relativePath = filename.replace("\\", "/");
        if (relativePath.startsWith("sandbox/")) {
            relativePath = relativePath.substring("sandbox/".length());
        }
        if (relativePath.startsWith("/sandbox/")) {
            relativePath = relativePath.substring("/sandbox/".length());
        }
        String url = "/sandbox/" + relativePath;

        if (autoOpenBrowser) {
            try {
                openPath(filePath.toString());
                return "✅ 已在浏览器中打开 " + filename + "\n🔗 访问地址: " + url;
            } catch (Exception e) {
                return "✅ 预览就绪，请手动访问: " + url + "（自动打开失败：" + e.getMessage() + "）";
            }
        }
        return "✅ 预览就绪，请手动访问: " + url;
    }

    // @anchor: htmlPreviewer_openPath
    // 根据平台调用系统命令打开文件
    private void openPath(String path) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", "start", "", path);
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", path);
        } else {
            pb = new ProcessBuilder("xdg-open", path);
        }
        pb.redirectErrorStream(true);
        pb.start();
    }
}