package com.myagent.workflow.model;

import java.util.List;

// @anchor: methodDefinition_class
// 方法结构 record：名称/返回类型/参数/修饰符/行范围/关联锚点
public record MethodDefinition(
        String name,
        String returnType,
        List<String> parameters, // 参数列表，如 ["String name", "int age"]
        String modifiers,        // 如 "public static"
        int startLine,
        int endLine,
        String anchorId          // 如果方法有锚点，关联；否则 null
) {}
