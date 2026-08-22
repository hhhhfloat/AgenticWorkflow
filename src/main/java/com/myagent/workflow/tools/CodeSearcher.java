package com.myagent.workflow.tools;

// @anchor: codeSearcher_class
/**
 * 代码搜索门面（Facade）：对外提供统一的搜索工具入口，
 * 内部委托给三个职责单一的模块：
 * - TextSearcher        ：全文正则搜索（searchText）
 * - ReferenceFinder     ：引用/调用点查找（findReferences / findCallers）
 * - CallGraphAnalyzer   ：调用图分析（findCallees）
 * 公开方法签名保持不变，ToolExecutor 等调用方无需改动。
 */
public class CodeSearcher {
    private final TextSearcher textSearcher;
    private final ReferenceFinder referenceFinder;
    private final CallGraphAnalyzer callGraphAnalyzer;

    public CodeSearcher(AnchorManager anchorMgr) {
        this.textSearcher = new TextSearcher();
        this.referenceFinder = new ReferenceFinder();
        this.callGraphAnalyzer = new CallGraphAnalyzer(anchorMgr);
    }

    // @anchor: codeSearcher_searchText
    /**
     * 全文正则搜索
     */
    String searchText(String keyword, String filePattern, String path) {
        return textSearcher.searchText(keyword, filePattern, path);
    }

    // @anchor: codeSearcher_findReferences
    /**
     * 查找符号引用
     */
    String findReferences(String symbol, String path, String filePattern) {
        return referenceFinder.findReferences(symbol, path, filePattern);
    }

    // @anchor: codeSearcher_findCallers
    /**
     * 查找函数调用点
     */
    String findCallers(String functionName, String path, String filePattern) {
        return referenceFinder.findCallers(functionName, path, filePattern);
    }

    // @anchor: codeSearcher_findCallees
    /**
     * 分析函数调用依赖（支持递归与深度限制）
     */
    String findCallees(String functionName, String path, boolean recursive, Integer depth) {
        return callGraphAnalyzer.findCallees(functionName, path, recursive, depth);
    }
}
