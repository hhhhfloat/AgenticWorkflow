package com.myagent.workflow.parser;

import com.myagent.workflow.model.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 将 FileStructure 转换为精简文本格式，减少 Agent 接收的冗余信息
 */
public final class FileStructureFormatter {

    public static String format(FileStructure fs) {
        StringBuilder sb = new StringBuilder();
        sb.append("📄 ").append(fs.filePath()).append("\n");
        sb.append("语言: ").append(fs.language()).append("\n");

        if (fs.packageName() != null && !fs.packageName().isEmpty()) {
            sb.append("包: ").append(fs.packageName()).append("\n");
        }

        if (!fs.imports().isEmpty()) {
            sb.append("导入: ").append(String.join(", ", fs.imports())).append("\n");
        }

        // 类
        for (ClassDefinition cls : fs.classes()) {
            sb.append("\n📦 类 ").append(cls.name());
            if (cls.superClass() != null) {
                sb.append(" extends ").append(cls.superClass());
            }
            if (!cls.interfaces().isEmpty()) {
                sb.append(" implements ").append(String.join(", ", cls.interfaces()));
            }
            sb.append(" [").append(cls.startLine()).append("-").append(cls.endLine()).append("]\n");

            // 字段
            if (!cls.fields().isEmpty()) {
                sb.append("  字段:\n");
                for (FieldDefinition f : cls.fields()) {
                    sb.append("    ").append(formatField(f)).append("\n");
                }
            }

            // 方法
            if (!cls.methods().isEmpty()) {
                sb.append("  方法:\n");
                for (MethodDefinition m : cls.methods()) {
                    sb.append("    ").append(formatMethod(m)).append("\n");
                }
            }
        }

        // 顶层函数（Python/JS）
        if (!fs.functions().isEmpty()) {
            sb.append("\n顶层函数:\n");
            for (MethodDefinition fn : fs.functions()) {
                sb.append("  ").append(formatMethod(fn)).append("\n");
            }
        }

        // 修改 FileStructureFormatter 中的锚点输出部分
        if (!fs.anchors().isEmpty()) {
            sb.append("\n📍 锚点:\n");
            for (AnchorSummary a : fs.anchors()) {
                sb.append("  line[").append(a.line()).append("] ").append(a.preview()).append("\n");
            }
        }

        return sb.toString();
    }

    private static String formatMethod(MethodDefinition m) {
        StringBuilder sb = new StringBuilder();
        if (m.modifiers() != null && !m.modifiers().isEmpty()) {
            String mod = m.modifiers().replace("[", "").replace("]", "").replace(",", " ");
            sb.append(mod).append(" ");
        }
        sb.append(m.returnType()).append(" ").append(m.name());
        sb.append("(").append(String.join(", ", m.parameters())).append(")");
        sb.append(" [").append(m.startLine()).append("-").append(m.endLine()).append("]");
        return sb.toString();
    }

    private static String formatField(FieldDefinition f) {
        StringBuilder sb = new StringBuilder();
        if (f.modifiers() != null && !f.modifiers().isEmpty()) {
            String mod = f.modifiers().replace("[", "").replace("]", "").replace(",", " ");
            sb.append(mod).append(" ");
        }
        sb.append(f.type()).append(" ").append(f.name());
        sb.append(" [行 ").append(f.line()).append("]");
        return sb.toString();
    }
}