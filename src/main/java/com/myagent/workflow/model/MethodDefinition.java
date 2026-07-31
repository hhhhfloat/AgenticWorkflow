package com.myagent.workflow.model;

import java.util.List;

public record MethodDefinition(
        String name,
        String returnType,
        List<String> parameters, // 参数列表，如 ["String name", "int age"]
        String modifiers,        // 如 "public static"
        int startLine,
        int endLine,
        String anchorId          // 如果方法有锚点，关联；否则 null
) {}