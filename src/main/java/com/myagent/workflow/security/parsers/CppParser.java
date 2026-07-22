package com.myagent.workflow.security.parsers;

// 基本与 JavaParser 相同，因为 C++ 也有 // 和 /* */。
// 可直接复用 JavaParser，或者单独实现，但为了扩展性我们单独创建。
public class CppParser extends JavaParser {
    // C++ 注释规则与 Java 一致，直接继承即可
}