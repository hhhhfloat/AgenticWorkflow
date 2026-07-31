package com.myagent.workflow.model;

public record FieldDefinition(
        String name,
        String type,
        String modifiers,      // 如 "private final"
        int line
) {}