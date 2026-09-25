// @anchor: securityCodeParser_tot_desc
// 安全扫描代码解析器接口：从源码中提取去除注释后的有效代码行
package com.myagent.workflow.security.parsers;

import com.myagent.workflow.security.CodeLine;
import java.util.List;

// @anchor: securityCodeParser_class
// 代码解析器接口：抽象“提取有效代码行”能力，屏蔽各语言注释差异
public interface CodeParser {
    // @anchor: securityCodeParser_extractEffectiveLines
    // 提取有效代码行（去除注释与空行，并保留每行的原始行号）
    /**
     * 从源代码中提取有效代码行（去除注释和空行）
     * @param source 完整的源码字符串
     * @return 有效代码行列表（包含行号）
     */
    List<CodeLine> extractEffectiveLines(String source);
}
