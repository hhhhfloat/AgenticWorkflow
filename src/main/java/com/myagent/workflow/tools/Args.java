// @anchor: args_intro
// 工具参数包装：对模型传入的 JSON 参数做类型宽容提取，避免强转崩溃
package com.myagent.workflow.tools;

import java.util.Map;

// @anchor: args_class
// 参数包装：getString/getBool/getInt 安全取值，兼容模型传字符串/数字/布尔混用
public final class Args {

    private final Map<String, Object> raw;

    // @anchor: args_constructor
    public Args(Map<String, Object> raw) {
        this.raw = (raw != null) ? raw : Map.of();
    }

    // @anchor: args_getString
    // 取字符串：key 缺失或值为 null 时返回 null；否则返回 toString()
    public String getString(String key) {
        Object v = raw.get(key);
        return (v == null) ? null : v.toString();
    }

    // @anchor: args_getStringDefault
    // 取字符串：缺失 / null / 空白 均返回默认值
    public String getString(String key, String defaultValue) {
        Object v = raw.get(key);
        if (v == null) return defaultValue;
        String s = v.toString().trim();
        return s.isEmpty() ? defaultValue : s;
    }

    // @anchor: args_getBool
    // 取布尔：兼容 Boolean / Number（非 0 为 true）/ String（"true" 大小写不敏感）
    public boolean getBool(String key, boolean defaultValue) {
        Object v = raw.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = v.toString().trim();
        if (s.isEmpty()) return defaultValue;
        return Boolean.parseBoolean(s);
    }

    // @anchor: args_getInt
    // 取整数：兼容 Number（intValue）/ 数字字符串；解析失败返回默认值
    public int getInt(String key, int defaultValue) {
        Object v = raw.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}