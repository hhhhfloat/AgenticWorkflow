package com.myagent.workflow.model;

import java.util.List;

// @anchor: fileStructure_class
// 文件结构 record：解析产物顶层容器（包/导入/类/函数/字段/锚点）
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
