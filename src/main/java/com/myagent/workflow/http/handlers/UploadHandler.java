package com.myagent.workflow.http.handlers;

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

public class UploadHandler implements HttpHandler {
    private static final int MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final Pattern SAFE_NAME = Pattern.compile("^(?!.*\\.\\.)[^\\\\/:*?\"<>|]+$");

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
        MultipartData multipartData = parseMultipart(exchange.getRequestBody(), boundary);
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

    /**
     * 解析 multipart/form-data 请求体
     * 注意：此实现不依赖第三方库，但假设文件数据不大（<100MB）
     */
    private MultipartData parseMultipart(InputStream inputStream, String boundary) throws IOException {
        MultipartData result = new MultipartData();
        result.files = new ArrayList<>();

        String boundaryLine = "--" + boundary;
        String endBoundary = "--" + boundary + "--";

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (true) {
            int read = inputStream.read(chunk);
            if (read == -1) break;
            buffer.write(chunk, 0, read);
        }

        byte[] data = buffer.toByteArray();
        // 使用 ISO-8859-1 解码整个请求体，因为 multipart 头部是 ASCII 兼容的
        String content = new String(data, StandardCharsets.ISO_8859_1);

        // 按 boundary 分割
        String[] parts = content.split(boundaryLine);
        for (String part : parts) {
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
                    // 1. 尝试匹配 filename*= （RFC 5987 编码）
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename\\*=(?:UTF-8'')([^;]+)").matcher(line);
                    if (m.find()) {
                        String encoded = m.group(1);
                        try {
                            filename = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
                        } catch (Exception e) {
                            // 解码失败，回退
                            filename = encoded;
                        }
                    } else {
                        // 2. 回退到 filename= （旧式，ISO-8859-1 编码）
                        m = java.util.regex.Pattern.compile("filename=\"([^\"]*)\"").matcher(line);
                        if (m.find()) {
                            String raw = m.group(1);
                            // 将 ISO-8859-1 字符串转回字节，再用 UTF-8 解码
                            filename = new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                        }
                    }

                    // 提取 name
                    m = java.util.regex.Pattern.compile("name=\"([^\"]*)\"").matcher(line);
                    if (m.find()) {
                        name = m.group(1);
                    }
                    break; // 已处理 Content-Disposition，跳出循环
                }
            }

            if ("projectName".equals(name)) {
                result.projectName = body.trim();
            } else if (name != null && name.startsWith("files") && filename != null && !filename.isEmpty()) {
                // 处理文件内容（保持二进制数据）
                // 注意：body 是从 ISO-8859-1 字符串中截取的子串，需要转回字节
                byte[] fileBytes = body.getBytes(StandardCharsets.ISO_8859_1);
                // 去掉末尾的 \r\n
                int trimLen = fileBytes.length;
                while (trimLen > 0 && (fileBytes[trimLen - 1] == '\r' || fileBytes[trimLen - 1] == '\n')) {
                    trimLen--;
                }
                // 还需要去掉开头的 \r\n？通常 body 开头就是文件内容，没有多余换行，但为了安全，可以跳过
                // 如果 body 以 \r\n 开头，可能是因为前面有额外的换行，但一般不会
                // 这里保持原样

                MultipartFile mf = new MultipartFile();
                mf.fileName = filename;
                mf.data = new ByteArrayInputStream(fileBytes, 0, trimLen);
                result.files.add(mf);
            }
        }

        return result;
    }

    // 内部数据类
    private static class MultipartData {
        String projectName;
        List<MultipartFile> files;
    }

    private static class MultipartFile {
        String fileName;
        InputStream data;
    }
}

