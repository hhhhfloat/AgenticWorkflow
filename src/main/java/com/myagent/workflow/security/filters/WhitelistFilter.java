package com.myagent.workflow.security.filters;

import com.myagent.workflow.security.CodeLine;

public class WhitelistFilter {
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