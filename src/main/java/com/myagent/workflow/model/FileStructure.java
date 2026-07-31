package com.myagent.workflow.model;

import java.util.List;

public record FileStructure(
        String filePath,
        String language,
        String packageName,      // 包/命名空间，无则为 null
        List<String> imports,    // 导入语句
        List<ClassDefinition> classes,
        List<MethodDefinition> functions, // 顶层函数（Python/JS）
        List<FieldDefinition> fields,     // 顶层变量
        List<AnchorSummary> anchors          // 文件级锚点
) {}