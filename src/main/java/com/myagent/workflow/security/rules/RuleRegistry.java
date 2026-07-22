package com.myagent.workflow.security.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public class RuleRegistry {
    private static final Logger logger = LoggerFactory.getLogger(RuleRegistry.class);
    private static final RuleRegistry INSTANCE = new RuleRegistry();
    private final List<Rule> rules = new ArrayList<>();

    private RuleRegistry() {
        // 注册所有规则
        registerRule(new FilePathRule());
        registerRule(new CommandExecutionRule());
        logger.info("已注册 {} 条安全规则", rules.size());
    }

    public static RuleRegistry getInstance() {
        return INSTANCE;
    }

    private void registerRule(Rule rule) {
        if (rule.isEnabled()) {
            rules.add(rule);
        }
    }

    public List<Rule> getRules() {
        return Collections.unmodifiableList(rules);
    }
}