// @anchor: whitelistFilter_tot_desc
// 白名单过滤器：放行 import/from/#include 等无害的导入类语句
package com.myagent.workflow.security.filters;

import com.myagent.workflow.security.CodeLine;

// @anchor: whitelistFilter_class
// 白名单过滤器：识别并放行各语言中的导入/包含类安全语句
public class WhitelistFilter {
    // @anchor: whitelistFilter_isWhitelisted
    // 判断某行是否为安全的导入/包含语句，若是则跳过规则匹配
    /**
     * 判断某行是否为安全的“导入”或“包含”语句，应放行
     */
    public boolean isWhitelisted(CodeLine line, String language) {
        String content = line.content();
        // 通用：导入语句通常以 import/from/include 开头
        if (content.matches("(?i)^(import|from|#include)\\s+.*")) {
            return true;
        }
        // 特定语言细节：C++ 的 using namespace? 也可以放行，暂不处理
        return false;
    }
}
