// @anchor: uploadHandler_tot_desc
// 上传处理器：POST /upload，解析 multipart 表单并把文件写入 sandbox 项目
package com.myagent.workflow.http.handlers.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.myagent.workflow.http.utils.HandlerUtils.sendResponse;

// @anchor: uploadHandler_class
// 上传处理器：自带 multipart 解析，校验项目名与文件名后保存上传文件
public class UploadHandler implements HttpHandler {
    private static final int MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final long MAX_REQUEST_SIZE = MAX_FILE_SIZE + 1_000_000L;
    private static final Pattern SAFE_NAME = Pattern.compile("^(?!.*\\.\\.)[^\\\\/:*?\"<>|]+$");

    // @anchor: uploadHandler_handle
    // 处理上传请求：解析 multipart、安全校验、存在性检测并保存文件
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 检查是否强制覆盖模式
        boolean force = false;
        String query = exchange.getRequestURI().getQuery();
        if (query != null && query.contains("force=true")) {
            force = true;
        }

        // 解析 multipart/form-data
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"请使用 multipart/form-data 格式上传\"}");
            return;
        }

        // 提取 boundary
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"无法解析 boundary\"}");
            return;
        }

        // 解析 multipart 请求
        MultipartData multipartData;
        try {
            multipartData = parseMultipart(exchange.getRequestBody(), boundary);
        } catch (IOException e) {
            sendResponse(exchange, 413,
                    "{\"status\":\"error\", \"message\":\"" + e.getMessage() + "\"}");
            return;
        }
        if (multipartData == null || multipartData.projectName == null || multipartData.files.isEmpty()) {
            sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"缺少项目名或文件\"}");
            return;
        }

        String projectName = multipartData.projectName;
        // 安全校验：项目名只允许字母、数字、连字符、下划线
        if (!SAFE_NAME.matcher(projectName).matches()) {
            sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"项目名不合法，仅允许字母、数字、- 和 _\"}");
            return;
        }

        // 目标目录：sandbox/{projectName}/
        Path projectDir = Paths.get("./sandbox", projectName).normalize();
        Path sandboxRoot = Paths.get("./sandbox").normalize();
        if (!projectDir.startsWith(sandboxRoot)) {
            sendResponse(exchange, 403, "{\"status\":\"error\", \"message\":\"路径非法\"}");
            return;
        }

        // 确保项目目录存在
        if (!Files.exists(projectDir)) {
            Files.createDirectories(projectDir);
        }

        // 检查哪些文件已存在
        List<String> existingFiles = new ArrayList<>();
        List<String> uploadedFiles = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();

        for (MultipartFile file : multipartData.files) {
            String fileName = file.fileName;
            // 安全校验：文件名不允许路径穿越
            if (!SAFE_NAME.matcher(fileName).matches() || fileName.contains("..")) {
                failedFiles.add(fileName + " (不合法文件名)");
                continue;
            }

            Path target = projectDir.resolve(fileName).normalize();
            if (!target.startsWith(projectDir)) {
                failedFiles.add(fileName + " (路径非法)");
                continue;
            }

            // 检查是否存在
            if (Files.exists(target) && !force) {
                existingFiles.add(fileName);
                continue;
            }

            // 保存文件
            try {
                if (Files.exists(target)) {
                    Files.delete(target);
                }
                Files.copy(file.data, target, StandardCopyOption.REPLACE_EXISTING);
                uploadedFiles.add(fileName);
            } catch (IOException e) {
                failedFiles.add(fileName + " (" + e.getMessage() + ")");
            }
        }

        // 如果存在且非强制模式，返回已存在列表让前端确认
        if (!existingFiles.isEmpty() && !force) {
            String json = "{\"status\":\"check\", \"existing\":" +
                    new ObjectMapper().writeValueAsString(existingFiles) +
                    ", \"message\":\"" + existingFiles.size() + " 个文件已存在\"}";
            sendResponse(exchange, 200, json);
            return;
        }

        // 构建响应
        Map<String, Object> result = new LinkedHashMap<>();
        if (!uploadedFiles.isEmpty() || !failedFiles.isEmpty()) {
            result.put("status", failedFiles.isEmpty() ? "success" : "partial");
            result.put("uploaded", uploadedFiles);
            result.put("failed", failedFiles);
            result.put("count", uploadedFiles.size());
            sendResponse(exchange, 200, new ObjectMapper().writeValueAsString(result));
        } else {
            sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"没有文件被上传\"}");
        }
    }

    // @anchor: uploadHandler_extractBoundary
    // 从 Content-Type 头中提取 multipart 的 boundary 值
    /**
     * 从 Content-Type 提取 boundary
     */
    private String extractBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                return part.substring("boundary=".length());
            }
        }
        return null;
    }

    // @anchor: uploadHandler_parseMultipart
    // 解析 multipart 请求体：切分字段与文件，处理文件名编码
    /**
     * 解析 multipart/form-data 请求体。
     * <p>
     * 加固点：
     * - 读取请求体时累计字节数，超过 MAX_REQUEST_SIZE 立即中止（防止 OOM）
     * - 用 indexOf 逐个扫描 boundary，避免 String.split 的正则处理与峰值内存
     */
    private MultipartData parseMultipart(InputStream inputStream, String boundary) throws IOException {
        MultipartData result = new MultipartData();
        result.files = new ArrayList<>();

        String boundaryLine = "--" + boundary;

        // ===== 1. 读取请求体，累计字节数超限即中止 =====
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long totalRead = 0;
        while (true) {
            int read = inputStream.read(chunk);
            if (read == -1) break;
            totalRead += read;
            if (totalRead > MAX_REQUEST_SIZE) {
                throw new IOException("上传请求体超过大小上限 "
                        + (MAX_REQUEST_SIZE / 1024 / 1024) + "MB");
            }
            buffer.write(chunk, 0, read);
        }

        byte[] data = buffer.toByteArray();
        // 使用 ISO-8859-1 解码整个请求体，因为 multipart 头部是 ASCII 兼容的
        String content = new String(data, StandardCharsets.ISO_8859_1);

        // ===== 2. 用 indexOf 逐个扫描 boundary =====
        int from = 0;
        while (true) {
            int start = content.indexOf(boundaryLine, from);
            if (start < 0) break;
            int bodyStart = start + boundaryLine.length();
            int next = content.indexOf(boundaryLine, bodyStart);
            String part = (next < 0)
                    ? content.substring(bodyStart)
                    : content.substring(bodyStart, next);
            from = (next < 0) ? content.length() : next;

            if (part.trim().startsWith("--")) continue; // 跳过结束边界

            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd == -1) continue;

            String headers = part.substring(0, headerEnd);
            String body = part.substring(headerEnd + 4);

            // 解析 Content-Disposition
            String name = null;
            String filename = null;
            for (String line : headers.split("\r\n")) {
                if (line.startsWith("Content-Disposition:")) {
                    java.util.regex.Matcher m = Pattern.compile("filename\\*=(?:UTF-8'')([^;]+)").matcher(line);
                    if (m.find()) {
                        String encoded = m.group(1);
                        try {
                            filename = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
                        } catch (Exception e) {
                            filename = encoded;
                        }
                    } else {
                        m = Pattern.compile("filename=\"([^\"]*)\"").matcher(line);
                        if (m.find()) {
                            String raw = m.group(1);
                            filename = new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                        }
                    }

                    m = Pattern.compile("name=\"([^\"]*)\"").matcher(line);
                    if (m.find()) {
                        name = m.group(1);
                    }
                    break;
                }
            }

            if ("projectName".equals(name)) {
                result.projectName = body.trim();
            } else if (name != null && name.startsWith("files") && filename != null && !filename.isEmpty()) {
                byte[] fileBytes = body.getBytes(StandardCharsets.ISO_8859_1);
                int trimLen = fileBytes.length;
                while (trimLen > 0 && (fileBytes[trimLen - 1] == '\r' || fileBytes[trimLen - 1] == '\n')) {
                    trimLen--;
                }

                MultipartFile mf = new MultipartFile();
                mf.fileName = filename;
                mf.data = new ByteArrayInputStream(fileBytes, 0, trimLen);
                result.files.add(mf);
            }
        }

        return result;
    }

    // 内部数据类
    // @anchor: uploadHandler_multipartData
    // multipart 解析结果：项目名与文件列表
    private static class MultipartData {
        String projectName;
        List<MultipartFile> files;
    }

    // @anchor: uploadHandler_multipartFile
    // 单个上传文件：文件名与数据流
    private static class MultipartFile {
        String fileName;
        InputStream data;
    }
}

