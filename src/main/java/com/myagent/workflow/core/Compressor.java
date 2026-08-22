package com.myagent.workflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 压缩器 —— 独立于上下文管理，只负责调用 Flash API 压缩文本。
 * 输入是原始上下文的字符串列表，输出是压缩后的摘要文本。
 * 不涉及消息格式、角色、存储等逻辑。
 */
public class Compressor {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public Compressor(OkHttpClient httpClient, ObjectMapper objectMapper, String apiKey) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    /**
     * 压缩 PROJECT.md 文档。
     * @param rawContent 完整的 PROJECT.md 内容
     * @return 压缩后的摘要（800-1500 字，保留锚点和目录树）
     */
    public String compressProjectMd(String rawContent) throws IOException {
        if (rawContent == null || rawContent.length() < 3000) {
            return rawContent; // 内容不长，直接返回原文
        }

        String prompt = """
            你是项目文档压缩专家。请将以下 PROJECT.md 压缩为结构清晰的摘要版本。

            【核心要求】
            1. 保留完整的目录树结构（使用缩进格式，保留所有层级关系）
            2. 保留所有 @anchor 锚点标记（格式：// @anchor: 名称 或对应语言的注释格式）
            3. 保留功能清单和关键锚点的对应关系
            4. 删除具体代码实现细节、冗余解释文字、示例代码
            5. 输出字数控制在 800-1500 字之间
            6. 保持原始 Markdown 结构

            【重要】绝对不要删除任何 @anchor 标记！

            【原始 PROJECT.md 内容】
            """ + rawContent;

        return callFlash(prompt);
    }

    /**
     * 压缩上下文状态（Agent 提供的摘要 + 工作区内容）。
     * @param phaseSummary Agent 提供的阶段总结（含模板）
     * @param nextPlan 下一步计划
     * @param workingContent 当前工作区的序列化内容
     * @param projectMdSummary PROJECT.md 压缩版（可选）
     * @return 压缩后的状态摘要（严格按模板输出）
     */
    public String compressContext(String phaseSummary, String nextPlan,
                                  String workingContent, String projectMdSummary,
                                  String docsSummary) throws IOException {
        StringBuilder contextBuilder = new StringBuilder();

        contextBuilder.append("【当前阶段总结（Agent 提供）】\n");
        contextBuilder.append(phaseSummary).append("\n\n");

        contextBuilder.append("【下一步计划】\n");
        contextBuilder.append(nextPlan).append("\n\n");

        if (projectMdSummary != null && !projectMdSummary.isBlank()) {
            contextBuilder.append("【项目结构快照（PROJECT.md 压缩版）】\n");
            contextBuilder.append(projectMdSummary).append("\n\n");
        }

        // ✅ 新增：加入文档变更摘要
        if (docsSummary != null && !docsSummary.isBlank()) {
            contextBuilder.append("【本次周期内文档变更】\n");
            contextBuilder.append(docsSummary).append("\n\n");
        }

        if (workingContent != null && !workingContent.isBlank()) {
            contextBuilder.append("【最近工作区内容】\n");
            contextBuilder.append(workingContent).append("\n\n");
        }

        String prompt = """
        你是上下文状态压缩专家。请根据以下输入，生成一个结构化的项目状态摘要。
        
        【输出要求】
        必须严格按以下模板输出，不得增减字段：
    
        ## PROJECT_STATE_SNAPSHOT
        - TOTAL_GOAL: [最终目标，一句话概括]
        - COMPLETED: [已完成的全部关键功能，用逗号分隔，不超过 300 字]
        - NEXT_TASKS: [下一步具体行动，一句话描述]
        - DIRTY_FILES: [本次周期内修改的核心文件列表，用逗号分隔]
        - TARGET_FILES: [下一步需要操作的文件路径，用逗号分隔]
    
        【重要】只输出上述模板内容，不要添加任何额外说明、评价或分析。
        
        【待压缩内容】
        """ + contextBuilder.toString();

        return callFlash(prompt);
    }

    /**
     * 调用 DeepSeek Flash 模型。
     */
    private String callFlash(String prompt) throws IOException {
        Map<String, Object> requestBody = Map.of(
                "model", AgentConfig.getModelFlash(),
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "max_tokens", 4096,
                "temperature", 0.3
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        Request httpRequest = new Request.Builder()
                .url(AgentConfig.getApiUrl())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Flash API 请求失败: " + response.code() + " " + response.message());
            }
            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("Flash 响应异常: " + responseBody);
            }
            JsonNode messageNode = choices.get(0).get("message");
            if (messageNode == null || !messageNode.has("content")) {
                throw new IOException("Flash 响应缺少 content");
            }
            return messageNode.get("content").asText();
        }
    }
}