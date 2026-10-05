// @anchor: classDefinition_intro
// 类定义结构：名称、类型、父类、成员与锚点、行范围
package com.myagent.workflow.model;

import java.util.List;

// @anchor: classDefinition_class
// 类结构 record：承载类型/父类/接口/方法/字段/类内锚点与行范围
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
