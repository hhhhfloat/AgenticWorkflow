package com.myagent.workflow.model;

import java.util.List;

public record ClassDefinition(
        String name,
        String type,             // "class", "interface", "enum", "record", "struct"
        String superClass,       // 父类/基类，无则为 null
        List<String> interfaces, // 实现的接口/协议
        List<MethodDefinition> methods,    // ✅ 改为你的 MethodDefinition
        List<FieldDefinition> fields,      // ✅ 改为你的 FieldDefinition
        List<AnchorSummary> anchors,       // 类内锚点
        int startLine,
        int endLine
) {}