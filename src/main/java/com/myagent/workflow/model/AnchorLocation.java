package com.myagent.workflow.model;

// @anchor: anchorLocation_class
// 锚点位置 POJO：承载 findAnchor 返回的文件/行号/预览
/**
 * 原为 Main 的内部类，现独立为顶层类。
 */
public class AnchorLocation {
    public String projectPath;
    public String filePath;
    public int line;
    public String id;
    public String preview;
}
