package com.myagent.workflow.testtool.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.myagent.workflow.testtool.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试档案写入器
 * 生成 ./testPortfolio/run_xxx/ 目录下的完整测试档案
 */
public class PortfolioWriter {

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private static final DateTimeFormatter RUN_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * 主入口
     */
    public static void write(
            TestResult resultA,
            List<IterationData> iterationsA,
            TestResult resultB,
            List<IterationData> iterationsB,
            String fullLog,
            String prompt,
            int maxIterations,
            int minInterval,
            int maxInterval,
            String model
    ) throws IOException {

        // ----- 1. 识别压缩组 -----
        boolean aIsCompressed = resultA.testName().contains("启用压缩");
        TestResult compressed = aIsCompressed ? resultA : resultB;
        TestResult uncompressed = aIsCompressed ? resultB : resultA;
        List<IterationData> compressedIters = aIsCompressed ? iterationsA : iterationsB;
        List<IterationData> uncompressedIters = aIsCompressed ? iterationsB : iterationsA;
        String compressedId = aIsCompressed ? "A" : "B";
        String uncompressedId = aIsCompressed ? "B" : "A";

        // ----- 2. 创建目录 -----
        String timestamp = LocalDateTime.now().format(RUN_ID_FMT);
        String runId = "run_" + timestamp;
        Path baseDir = Paths.get("./testPortfolio", runId);
        Path rawDir = baseDir.resolve("raw_data");
        Path configDir = baseDir.resolve("configs");
        Files.createDirectories(rawDir);
        Files.createDirectories(configDir);

        // ----- 3. 提取压缩轮次 -----
        List<Integer> compressedRounds = extractCompressionRounds(fullLog);
        List<Integer> uncompressedRounds = Collections.emptyList();

        // ----- 4. 构建两组数据 -----
        // 4a. 用于 Manifest（JSON）
        ManifestGroupSummary manifestA = new ManifestGroupSummary(
                compressedId,
                "min=" + minInterval + ", max=" + maxInterval,
                ThresholdConfig.from(true, minInterval, maxInterval),
                compressed.totalIterations(),
                compressed.totalCost(),
                compressed.compressionCount(),
                compressedRounds,
                compressed.cacheHitRate(),
                0
        );

        ManifestGroupSummary manifestB = new ManifestGroupSummary(
                uncompressedId,
                "压缩关闭",
                ThresholdConfig.disabled(),
                uncompressed.totalIterations(),
                uncompressed.totalCost(),
                uncompressed.compressionCount(),
                uncompressedRounds,
                uncompressed.cacheHitRate(),
                0
        );

        // 4b. 用于 Report（Markdown）
        ReportGroupSummary reportA = new ReportGroupSummary(
                compressedId,
                "min=" + minInterval + ", max=" + maxInterval,
                ThresholdConfig.from(true, minInterval, maxInterval),
                compressed.totalIterations(),
                compressed.totalCost(),
                compressed.compressionCount(),
                compressedRounds,
                compressed.cacheHitRate(),
                0
        );

        ReportGroupSummary reportB = new ReportGroupSummary(
                uncompressedId,
                "压缩关闭",
                ThresholdConfig.disabled(),
                uncompressed.totalIterations(),
                uncompressed.totalCost(),
                uncompressed.compressionCount(),
                uncompressedRounds,
                uncompressed.cacheHitRate(),
                0
        );

        // ----- 5. 判定胜者 -----
        Verdict verdict = determineVerdict(reportA, reportB);

        // ----- 6. 构建元数据 -----
        RunMeta meta = new RunMeta(
                runId,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                prompt,
                model,
                maxIterations
        );

        // ----- 7. 写入 0_manifest.json -----
        Manifest manifest = new Manifest(meta, List.of(manifestA, manifestB), verdict);
        JSON_MAPPER.writeValue(baseDir.resolve("0_manifest.json").toFile(), manifest);

        // ----- 8. 写入 CSV -----
        writeIterationsCsv(rawDir.resolve("group_a_iterations.csv"), compressedIters);
        writeIterationsCsv(rawDir.resolve("group_b_iterations.csv"), uncompressedIters);

        // ----- 9. 写入 configs/thresholds.json -----
        writeThresholdsConfig(configDir, reportA, reportB, model, maxIterations, prompt);

        // ----- 10. 写入 1_report.md -----
        ReportData reportData = new ReportData(meta, List.of(reportA, reportB), verdict, compressedIters, uncompressedIters);
        String markdown = ReportRenderer.render(reportData);
        Files.writeString(baseDir.resolve("1_report.md"), markdown, StandardCharsets.UTF_8);

        System.out.println("✅ 测试档案已生成: " + baseDir.toAbsolutePath());
    }

    // ==================== 辅助方法 ====================

    private static Verdict determineVerdict(ReportGroupSummary a, ReportGroupSummary b) {
        boolean aBetterIter = a.totalIterations() <= b.totalIterations();
        boolean aBetterCost = a.totalCost() <= b.totalCost();

        if (aBetterIter && aBetterCost) {
            return new Verdict(
                    a.id(),
                    String.format("压缩开启组（%s）在迭代轮数（%d vs %d）和成本（¥%.6f vs ¥%.6f）上均优于关闭组。",
                            a.thresholds().label(), a.totalIterations(), b.totalIterations(),
                            a.totalCost(), b.totalCost())
            );
        } else if (!aBetterIter && !aBetterCost) {
            return new Verdict(
                    b.id(),
                    String.format("压缩关闭组在迭代轮数（%d vs %d）和成本（¥%.6f vs ¥%.6f）上均优于开启组。（任务可能过短，压缩收益不明显）",
                            b.totalIterations(), a.totalIterations(),
                            b.totalCost(), a.totalCost())
            );
        } else {
            return new Verdict(
                    "混合（请人工判断）",
                    String.format("压缩开启组迭代更少（%d vs %d）但成本更高（¥%.6f vs ¥%.6f），或反之。建议结合任务复杂度综合判断。",
                            a.totalIterations(), b.totalIterations(),
                            a.totalCost(), b.totalCost())
            );
        }
    }

    private static void writeThresholdsConfig(Path configDir, ReportGroupSummary a, ReportGroupSummary b,
                                              String model, int maxIterations, String prompt) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("testSuite", "压缩时机对比测试");

        // 使用显式 Map，避免 Map.of 的 null 值限制
        List<Map<String, Object>> groups = new ArrayList<>();

        Map<String, Object> groupA = new LinkedHashMap<>();
        groupA.put("id", a.id());
        groupA.put("compressionEnabled", true);
        groupA.put("minInterval", 5);    // 可根据实际传入值调整，这里先固定
        groupA.put("maxInterval", 15);
        groups.add(groupA);

        Map<String, Object> groupB = new LinkedHashMap<>();
        groupB.put("id", b.id());
        groupB.put("compressionEnabled", false);
        groupB.put("minInterval", null);
        groupB.put("maxInterval", null);
        groups.add(groupB);

        map.put("groups", groups);

        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("model", model);
        shared.put("maxIterations", maxIterations);
        shared.put("prompt", prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt);
        map.put("shared", shared);

        JSON_MAPPER.writeValue(configDir.resolve("thresholds.json").toFile(), map);
    }

    static List<Integer> extractCompressionRounds(String fullLog) {
        List<Integer> rounds = new ArrayList<>();
        if (fullLog == null || fullLog.isEmpty()) return rounds;

        Pattern iterPattern = Pattern.compile("--- 第 (\\d+) 次迭代 ---");
        Pattern compressPattern = Pattern.compile("📌 \\[系统\\] 进入压缩模式");

        int lastIteration = 0;
        for (String line : fullLog.split("\n")) {
            Matcher im = iterPattern.matcher(line);
            if (im.find()) {
                lastIteration = Integer.parseInt(im.group(1));
                continue;
            }
            Matcher cm = compressPattern.matcher(line);
            if (cm.find() && lastIteration > 0) {
                rounds.add(lastIteration);
            }
        }
        return rounds;
    }

    private static void writeIterationsCsv(Path path, List<IterationData> iterations) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("轮次,输入Token,缓存命中Token,输出Token,累计命中率(%),本轮命中率(%),成本(¥)\n");
        for (IterationData d : iterations) {
            sb.append(d.iteration()).append(",")
                    .append(d.promptTokens()).append(",")
                    .append(d.cachedTokens()).append(",")
                    .append(d.completionTokens()).append(",")
                    .append(String.format("%.2f", d.cumulativeHitRate())).append(",")
                    .append(String.format("%.2f", d.currentRoundHitRate())).append(",")
                    .append(String.format("%.6f", d.cost())).append("\n");
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }
}