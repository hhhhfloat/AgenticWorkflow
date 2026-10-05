// @anchor: probe_intro
// 环境探测接口：每个实现负责一类工具链
package com.myagent.workflow.core.config.env;

import java.util.Map;

// @anchor: probe_class
// 探测单元：返回 key → 值；未探测到的值用空串
public interface Probe {
    Map<String, String> detect();
}