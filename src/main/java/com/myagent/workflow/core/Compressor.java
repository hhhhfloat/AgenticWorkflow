package com.myagent.workflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 压缩器 —— 独立于上下文管理，负责调用 Flash API 压缩 PROJECT.md。
 * 上下文压缩已由 Agent 自压缩代替（见 SystemPrompt 中的压缩模式）。
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
            return rawContent;
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