package com.myagent.workflow.testtool.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从日志文本中提取测试指标，构建 TestResult 对象
 * 属于 Model 层的数据转换工具
 */
public class ResultCollector {

    public static TestResult extractFromLog(String log, String testName) {
        int lastIteration = 0;
        int compressionCount = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        long cachedTokens = 0;
        double hitRate = 0.0;
        double cost = 0.0;

        String projectName = extractProjectNameFromLog(log);

        // 1. 提取迭代轮次
        Pattern iterationPattern = Pattern.compile("--- 第 (\\d+) 次迭代 ---");
        Matcher iterMatcher = iterationPattern.matcher(log);
        while (iterMatcher.find()) {
            lastIteration = Integer.parseInt(iterMatcher.group(1));
        }

        // 2. 提取压缩次数
        Pattern compressionPattern = Pattern.compile("进入压缩模式|开始压缩流程");
        Matcher compMatcher = compressionPattern.matcher(log);
        while (compMatcher.find()) {
            compressionCount++;
        }

        // 3. 提取成本统计块
        Pattern statsPattern = Pattern.compile(
                "📊 ========== 成本统计 ==========\\n(.*?)\\n==================================",
                Pattern.DOTALL
        );
        Matcher statsMatcher = statsPattern.matcher(log);
        if (statsMatcher.find()) {
            String statsBlock = statsMatcher.group(1);

            Pattern promptPattern = Pattern.compile("总输入 Token: (\\d+)");
            Matcher promptMatcher = promptPattern.matcher(statsBlock);
            if (promptMatcher.find()) {
                promptTokens = Long.parseLong(promptMatcher.group(1));
            }

            Pattern completionPattern = Pattern.compile("总输出 Token: (\\d+)");
            Matcher completionMatcher = completionPattern.matcher(statsBlock);
            if (completionMatcher.find()) {
                completionTokens = Long.parseLong(completionMatcher.group(1));
            }

            Pattern cachedPattern = Pattern.compile("缓存命中: (\\d+)");
            Matcher cachedMatcher = cachedPattern.matcher(statsBlock);
            if (cachedMatcher.find()) {
                cachedTokens = Long.parseLong(cachedMatcher.group(1));
            }

            Pattern hitRatePattern = Pattern.compile("总缓存命中率: ([\\d.]+)%");
            Matcher hitRateMatcher = hitRatePattern.matcher(statsBlock);
            if (hitRateMatcher.find()) {
                hitRate = Double.parseDouble(hitRateMatcher.group(1));
            }

            Pattern costPattern = Pattern.compile("总成本: ¥([\\d.]+)");
            Matcher costMatcher = costPattern.matcher(statsBlock);
            if (costMatcher.find()) {
                cost = Double.parseDouble(costMatcher.group(1));
            }
        }

        return new TestResult(
                testName,
                projectName,                 // ← 新增
                lastIteration,
                promptTokens,
                cachedTokens,
                completionTokens,
                cost,
                hitRate,
                compressionCount,
                0
        );
    }

    // 在 ResultCollector.java 中添加
    public static String extractProjectNameFromLog(String log) {
        // 1. 从 write_file 的第一个路径中提取
        Pattern writePattern = Pattern.compile("\"write_file\".*?\"filename\":\\s*\"([^/\"]+)/");
        Matcher writeMatcher = writePattern.matcher(log);
        if (writeMatcher.find()) {
            return writeMatcher.group(1);
        }

        // 2. 从 list_directory 的 path 参数中提取
        Pattern listPattern = Pattern.compile("\"list_directory\".*?\"path\":\\s*\"([^\"]+)\"");
        Matcher listMatcher = listPattern.matcher(log);
        while (listMatcher.find()) {
            String path = listMatcher.group(1);
            if (!path.equals(".") && !path.isEmpty()) {
                return path;
            }
        }

        // 3. 从用户提示词中提取（项目目录：xxx/）
        Pattern promptPattern = Pattern.compile("项目目录[:：]\\s*([^/\\s]+)/");
        Matcher promptMatcher = promptPattern.matcher(log);
        if (promptMatcher.find()) {
            return promptMatcher.group(1);
        }

        // 4. 默认回退
        return "unknown-project";
    }
}