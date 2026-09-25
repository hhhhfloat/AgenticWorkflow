// @anchor: securityCppParser_tot_desc
// C++ 注释解析器：直接继承 Java 解析器（注释语法一致）
package com.myagent.workflow.security.parsers;

// 基本与 JavaParser 相同，因为 C++ 也有 // 和 /* */。
// 可直接复用 JavaParser，或者单独实现，但为了扩展性我们单独创建。
// @anchor: securityCppParser_class
// C++ 解析器：复用 JavaParser 的注释剥离逻辑，仅作为独立扩展点存在
public class CppParser extends JavaParser {
    // C++ 注释规则与 Java 一致，直接继承即可
}
