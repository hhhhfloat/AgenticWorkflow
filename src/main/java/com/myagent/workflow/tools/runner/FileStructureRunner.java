// @anchor: fileStructureRunner_tot_desc
// 文件结构展示：代码文件走结构解析器，md/txt 走锚点描述转发
package com.myagent.workflow.tools.runner;

import com.myagent.workflow.model.FileStructure;
import com.myagent.workflow.parser.FileStructureFormatter;
import com.myagent.workflow.parser.StructureParser;
import com.myagent.workflow.parser.StructureParserRegistry;
import com.myagent.workflow.tools.PathUtils;
import com.myagent.workflow.tools.anchor.AnchorManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// @anchor: fileStructureRunner_class
// 文件结构展示器：解析文件并用格式化器输出精简结构
public class FileStructureRunner {

    private final AnchorManager anchorMgr;

    // @anchor: fileStructureRunner_constructor
    public FileStructureRunner(AnchorManager anchorMgr) {
        this.anchorMgr = anchorMgr;
    }

    // @anchor: fileStructureRunner_run
    // 解析文件并用格式化器输出精简的代码结构
    public String run(String filename) {
        try {
            Path filePath = PathUtils.safeResolve(filename);
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return "❌ 文件不存在: " + filename;
            }

            String lower = filename.toLowerCase();
            // md,txt 之类无代码结构，直接转发锚点描述
            if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")) {
                return anchorMgr.describeAnchorsByPath(filename);
            }

            StructureParser parser = StructureParserRegistry.getInstance().getParser(filePath);
            FileStructure structure = parser.parse(filePath);

            return FileStructureFormatter.format(structure);

        } catch (IOException e) {
            return "❌ 解析文件结构失败: " + e.getMessage();
        }
    }
}