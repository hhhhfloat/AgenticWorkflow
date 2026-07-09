package com.myagent.workflow;

/**
 * @anchor: anchorLocation_class
 * 锚点位置 POJO，用于 findAnchor 返回结果。
 * 原为 Main 的内部类，现独立为顶层类。
 */
public class AnchorLocation {
    public String projectPath;
    public String filePath;
    public int line;
    public String id;
    public String preview;
}
