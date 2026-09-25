// @anchor: codeLine_tot_desc
// 安全扫描用的源码行模型：携带去除注释后的文本内容与原始行号
package com.myagent.workflow.security;

// @anchor: codeLine_class
// 源码行 record：一条有效代码及其在文件中的原始行号，供规则匹配与报告定位
public record CodeLine(String content, int lineNumber) {}
