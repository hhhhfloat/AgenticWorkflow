// @anchor: ruleRegistry_tot_desc
// 规则注册中心：单例维护已启用的安全规则集合
package com.myagent.workflow.security.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

// @anchor: ruleRegistry_class
// 规则注册中心：单例注册并对外提供当前启用的规则列表
public class RuleRegistry {
    private static final Logger logger = LoggerFactory.getLogger(RuleRegistry.class);
    private static final RuleRegistry INSTANCE = new RuleRegistry();
    // @anchor: ruleRegistry_rules
    // 已启用的规则列表
    private final List<Rule> rules = new ArrayList<>();

    // @anchor: ruleRegistry_constructor
    // 私有构造：注册文件路径与命令执行规则并记录条数
    private RuleRegistry() {
        // 注册所有规则
        registerRule(new FilePathRule());
        registerRule(new CommandExecutionRule());
        logger.info("已注册 {} 条安全规则", rules.size());
    }

    // @anchor: ruleRegistry_getInstance
    // 获取全局单例
    public static RuleRegistry getInstance() {
        return INSTANCE;
    }

    // @anchor: ruleRegistry_registerRule
    // 注册单条规则（仅当规则自身标记为启用时加入列表）
    private void registerRule(Rule rule) {
        if (rule.isEnabled()) {
            rules.add(rule);
        }
    }

    // @anchor: ruleRegistry_getRules
    // 返回只读的已启用规则列表
    public List<Rule> getRules() {
        return Collections.unmodifiableList(rules);
    }
}
