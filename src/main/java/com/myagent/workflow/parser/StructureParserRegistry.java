// @anchor: structureParserRegistry_tot_desc
// 解析器注册中心：按文件扩展名分发到对应语言的结构解析器
package com.myagent.workflow.parser;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 解析器注册中心
 * 根据文件扩展名返回对应的解析器
 */
// @anchor: structureParserRegistry_class
// 解析器注册中心（单例）：维护扩展名到解析器的映射与兜底解析器
public final class StructureParserRegistry {

    private static final StructureParserRegistry INSTANCE = new StructureParserRegistry();
    private final Map<String, StructureParser> registry = new HashMap<>();
    private StructureParser defaultParser;

    // @anchor: structureParserRegistry_constructor
    // 私有构造：注册各语言解析器并设置通用兜底解析器
    private StructureParserRegistry() {
        // 注册各语言解析器
        defaultParser = new GenericParser();

        register(".java", new JavaParser());
        register(".py", new PythonParser());
        register(".cpp", new CppParser());
        register(".cc", new CppParser());
        register(".cxx", new CppParser());
        register(".h", new CppParser());
        register(".hpp", new CppParser());
        register(".hxx", new CppParser());
        register(".js", new JavaScriptParser());
        register(".jsx", new JavaScriptParser());
        register(".ts", new JavaScriptParser());
        register(".tsx", new JavaScriptParser());
        register(".mjs", new JavaScriptParser());
        register(".cjs", new JavaScriptParser());
        register(".html", new HtmlParser());
        register(".htm", new HtmlParser());
        register(".css", new CssParser());

    }

    // @anchor: structureParserRegistry_getInstance
    // 获取全局唯一实例
    public static StructureParserRegistry getInstance() {
        return INSTANCE;
    }

    // @anchor: structureParserRegistry_register
    // 把解析器实例绑定到指定文件扩展名
    /**
     * 注册一个解析器，绑定到指定的文件扩展名
     * @param extension 文件扩展名（如 ".java"）
     * @param parser 解析器实例
     */
    public void register(String extension, StructureParser parser) {
        registry.put(extension.toLowerCase(), parser);
    }

    // @anchor: structureParserRegistry_getParser
    // 按文件名后缀匹配解析器，未命中时返回默认解析器
    /**
     * 根据文件获取对应的解析器
     * @param file 文件路径
     * @return 对应的解析器，如果没有匹配则返回默认解析器
     */
    public StructureParser getParser(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        for (Map.Entry<String, StructureParser> entry : registry.entrySet()) {
            if (fileName.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return defaultParser;
    }

    // @anchor: structureParserRegistry_setDefaultParser
    // 设置未注册扩展名时使用的默认解析器
    /**
     * 设置默认解析器（用于未注册扩展名的文件）
     */
    public void setDefaultParser(StructureParser parser) {
        this.defaultParser = parser;
    }
}
