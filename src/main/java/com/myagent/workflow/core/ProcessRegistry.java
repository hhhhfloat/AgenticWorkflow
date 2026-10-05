// @anchor: processRegistry_intro
// 全局子进程登记：所有 Compiler 启动的顶层进程在此登记，JVM 退出时统一回收
package com.myagent.workflow.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// @anchor: processRegistry_class
// 进程登记表：注册/注销顶层进程，注册 shutdown hook 统一 killTree
public final class ProcessRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ProcessRegistry.class);

    private static final Set<Process> ACTIVE = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ProcessRegistry::killAll, "ProcessRegistry-Cleanup"));
    }

    private ProcessRegistry() {}

    // @anchor: processRegistry_register
    // 登记一个顶层进程；已终止的进程会被忽略
    public static void register(Process p) {
        if (p != null && p.isAlive()) {
            ACTIVE.add(p);
        }
    }

    // @anchor: processRegistry_unregister
    // 注销一个进程（正常结束时调用）
    public static void unregister(Process p) {
        if (p != null) {
            ACTIVE.remove(p);
        }
    }

    // @anchor: processRegistry_killAll
    // JVM 退出兜底：终止所有仍存活的登记进程及其后代
    private static void killAll() {
        if (ACTIVE.isEmpty()) return;
        logger.info("🧹 ProcessRegistry 退出清理: {} 个进程", ACTIVE.size());
        for (Process p : ACTIVE) {
            try {
                if (!p.isAlive()) continue;
                p.descendants().forEach(ph -> {
                    try { ph.destroyForcibly(); } catch (Exception ignored) {}
                });
                p.destroyForcibly();
            } catch (Exception ignored) {}
        }
        ACTIVE.clear();
    }
}