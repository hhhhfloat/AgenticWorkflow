package com.myagent.workflow.parser;

import com.myagent.workflow.model.FileStructure;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 文件结构解析器接口
 * 每种语言实现一个解析器，提取类、方法、字段、锚点等信息
 */
public interface StructureParser {

    /**
     * 检查是否支持解析该文件
     * @param file 文件路径
     * @return true 表示支持
     */
    boolean supports(Path file);

    /**
     * 解析文件，返回结构化信息
     * @param file 文件路径
     * @return FileStructure 对象
     * @throws IOException 读取失败时抛出
     */
    FileStructure parse(Path file) throws IOException;

    default String cleanLine(String rawLine) {
        return rawLine;
    }
    /**
     * 去除字符串和字符常量，保留注释和代码。
     * 用于锚点检测，防止字符串中的伪锚点被误报。
     * 默认实现不做任何处理，子类可覆盖。
     */
    default String stripStringsOnly(String rawLine) {
        return rawLine;
    }
}