package com.myagent.workflow.model;

// @anchor: fieldDefinition_class
// 字段结构 record：字段名/类型/修饰符/行号
public record FieldDefinition(
        String name,
        String type,
        String modifiers,      // 如 "private final"
        int line
) {}
