package com.myagent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @anchor: handlers_class
 * HTTP 处理器集合 —— 从 HttpServerMain.java 中提取的顶层类。
 * 包含：ProjectsHandler（项目列表）、StaticHandler（jar 内静态资源）、ExternalFileHandler（外部目录）。
 */
public final class Handlers {

    private Handlers() {
        // 工厂类，禁止实例化
    }

    // @anchor: handlers_projects
    /** 项目列表处理器：扫描 ./TestProjects 目录，按版本分组返回项目入口 */
    public static class ProjectsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            Path testProjectsDir = Paths.get("./TestProjects");
            Map<String, Object> result = new LinkedHashMap<>();
            List<Map<String, Object>> versions = new ArrayList<>();

            if (Files.exists(testProjectsDir) && Files.isDirectory(testProjectsDir)) {
                try (Stream<Path> versionDirs = Files.list(testProjectsDir)) {
                    versionDirs.filter(Files::isDirectory)
                            .sorted()
                            .forEach(versionDir -> {
                                String versionName = versionDir.getFileName().toString();
                                Map<String, Object> versionObj = new LinkedHashMap<>();
                                versionObj.put("version", versionName);

                                List<Map<String, String>> projects = new ArrayList<>();
                                try (Stream<Path> allPaths = Files.walk(versionDir)) {
                                    allPaths.filter(Files::isDirectory)
                                            .filter(dir -> Files.exists(dir.resolve("index.html")))
                                            .forEach(dir -> {
                                                Path relative = versionDir.relativize(dir);
                                                String relativePath = relative.toString().replace('\\', '/');
                                                String displayName = dir.getFileName().toString();

                                                if (relativePath.contains("/")) {
                                                    String[] parts = relativePath.split("/");
                                                    if (parts.length >= 2) {
                                                        displayName = parts[parts.length - 2] + "/" + parts[parts.length - 1];
                                                    } else {
                                                        displayName = relativePath;
                                                    }
                                                }

                                                String path = "../TestProjects/" + versionName + "/" + relativePath + "/index.html";
                                                Map<String, String> proj = new LinkedHashMap<>();
                                                proj.put("name", displayName);
                                                proj.put("path", path);
                                                projects.add(proj);
                                            });
                                } catch (IOException ignored) {}

                                versionObj.put("projects", projects);
                                versions.add(versionObj);
                            });
                } catch (IOException ignored) {}
            }

            result.put("versions", versions);
            String json = new ObjectMapper().writeValueAsString(result);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        }
    }

    // @anchor: handlers_static
    /** 内部静态资源处理器：处理 jar 包内的 /static 资源 */
    public static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) path = "/index.html";

            InputStream is = HttpServerMain.class.getResourceAsStream("/static" + path);
            if (is == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            String contentType = "text/html";
            if (path.endsWith(".css")) contentType = "text/css";
            else if (path.endsWith(".js")) contentType = "application/javascript";
            else if (path.endsWith(".png")) contentType = "image/png";
            else if (path.endsWith(".json")) contentType = "application/json";

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                is.transferTo(os);
            }
            exchange.close();
        }
    }

    // @anchor: handlers_external
    /** 外部目录处理器：处理磁盘上指定目录下的文件，支持自定义URL前缀 */
    public static class ExternalFileHandler implements HttpHandler {
        private final Path basePath;
        private final String prefix; // 如 "/TestProjects" 或 "/sandbox"

        public ExternalFileHandler(Path basePath, String prefix) {
            this.basePath = basePath.toAbsolutePath().normalize();
            this.prefix = prefix;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestPath = exchange.getRequestURI().getPath();
            // 移除前缀，获取相对路径
            if (!requestPath.startsWith(prefix)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String relative = requestPath.substring(prefix.length());
            if (relative.isEmpty() || relative.equals("/")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            Path resolved = basePath.resolve(relative.substring(1)).normalize();
            if (!resolved.startsWith(basePath)) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }

            File file = resolved.toFile();
            if (!file.exists()) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            if (file.isDirectory()) {
                file = new File(file, "index.html");
                if (!file.exists()) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
            }

            String name = file.getName();
            String contentType = "text/html";
            if (name.endsWith(".css")) contentType = "text/css";
            else if (name.endsWith(".js")) contentType = "application/javascript";
            else if (name.endsWith(".png")) contentType = "image/png";

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, file.length());
            try (FileInputStream fis = new FileInputStream(file);
                 OutputStream os = exchange.getResponseBody()) {
                fis.transferTo(os);
            }
            exchange.close();
        }
    }

    // ===================== 新增：目录浏览处理器 =====================
    public static class BrowseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // 获取查询参数 path
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);
            String path = params.getOrDefault("path", ".");

            // 安全检查：禁止路径穿越
            if (path.contains("..") || path.startsWith("/") || path.contains(":\\")) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }

            // 只允许访问 sandbox 和 TestProjects
            Path root = Paths.get(".").toAbsolutePath().normalize();
            Path target = root.resolve(path).normalize();
            Path sandboxRoot = root.resolve("sandbox").normalize();
            Path testRoot = root.resolve("TestProjects").normalize();

            if (!target.startsWith(sandboxRoot) && !target.startsWith(testRoot)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }

            if (!Files.exists(target) || !Files.isDirectory(target)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            List<Map<String, Object>> entries = new ArrayList<>();
            try (Stream<Path> stream = Files.list(target)) {
                stream.forEach(p -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    String name = p.getFileName().toString();
                    boolean isDir = Files.isDirectory(p);
                    item.put("name", name);
                    item.put("type", isDir ? "dir" : "file");

                    // 核心：检测该目录是否包含 index.html（用于 TestProjects 识别项目入口）
                    if (isDir) {
                        boolean hasIndex = Files.exists(p.resolve("index.html"));
                        item.put("hasIndexHtml", hasIndex);
                    }

                    if (!isDir) {
                        try {
                            item.put("size", Files.size(p));
                        } catch (IOException ignored) {}
                    }
                    entries.add(item);
                });
            }

            // 排序：目录在前，文件在后，按名称字母顺序
            entries.sort((a, b) -> {
                boolean aDir = "dir".equals(a.get("type"));
                boolean bDir = "dir".equals(b.get("type"));
                if (aDir && !bDir) return -1;
                if (!aDir && bDir) return 1;
                return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
            });

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", path);
            result.put("entries", entries);

            String json = new ObjectMapper().writeValueAsString(result);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        }

        private Map<String, String> parseQuery(String query) {
            Map<String, String> params = new HashMap<>();
            if (query == null) return params;
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    params.put(kv[0], kv[1]);
                }
            }
            return params;
        }
    }

    // ===================== 新增：归档处理器 =====================
    public static class ArchiveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 只接受 POST
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // 解析请求体
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root;
            String projectName;
            try {
                root = mapper.readTree(body);
                projectName = root.get("projectName").asText();
            } catch (Exception e) {
                sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"请求格式错误: " + e.getMessage() + "\"}");
                return;
            }

            // 安全检查：防止路径穿越
            if (projectName == null || projectName.trim().isEmpty() || projectName.contains("..") || projectName.contains("/") || projectName.contains("\\")) {
                sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"项目名不合法\"}");
                return;
            }

            // 源路径：sandbox/{projectName}
            Path src = Paths.get("./sandbox", projectName);
            if (!Files.exists(src) || !Files.isDirectory(src)) {
                sendResponse(exchange, 404, "{\"status\":\"error\", \"message\":\"项目不存在: " + projectName + "\"}");
                return;
            }

            // 目标路径：TestProjects/目前版本/{projectName}
            Path destDir = Paths.get("./TestProjects",AgentConfig.ARCHIVE_VERSION);
            if (!Files.exists(destDir)) {
                Files.createDirectories(destDir);
            }
            Path dest = destDir.resolve(projectName);

            // 检查是否已存在
            boolean force = false;
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("force=true")) {
                force = true;
            }

            if (Files.exists(dest) && !force) {
                sendResponse(exchange, 200, "{\"status\":\"exists\", \"message\":\"目标已存在，是否覆盖？\"}");
                return;
            }

            // 执行复制
            try {
                // 如果目标存在且 force=true，先删除
                if (Files.exists(dest)) {
                    deleteDirectory(dest);
                }
                // 递归复制
                copyDirectory(src, dest);
                sendResponse(exchange, 200, "{\"status\":\"success\", \"path\":\"TestProjects/" + AgentConfig.ARCHIVE_VERSION + "/" + projectName + "\"}");
            } catch (IOException e) {
                sendResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"复制失败: " + e.getMessage() + "\"}");
            }
        }

        /**
         * 递归复制目录
         */
        private void copyDirectory(Path src, Path dest) throws IOException {
            Files.walk(src).forEach(source -> {
                try {
                    Path target = dest.resolve(src.relativize(source));
                    if (Files.isDirectory(source)) {
                        if (!Files.exists(target)) {
                            Files.createDirectories(target);
                        }
                    } else {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        /**
         * 递归删除目录
         */
        private void deleteDirectory(Path dir) throws IOException {
            if (Files.exists(dir)) {
                Files.walk(dir)
                        .sorted((a, b) -> b.compareTo(a)) // 先删除子文件
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ===================== 新增：文件上传处理器 =====================
    public static class UploadHandler implements HttpHandler {
        private static final int MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
        private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9\\-_.]+$");

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
            byte[] boundaryBytes = boundaryLine.getBytes(StandardCharsets.US_ASCII);
            byte[] endBoundaryBytes = endBoundary.getBytes(StandardCharsets.US_ASCII);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            boolean readingHeader = true;
            StringBuilder header = new StringBuilder();
            boolean inFileContent = false;
            String currentName = null;
            String currentFilename = null;
            ByteArrayOutputStream currentData = null;

            while (true) {
                int read = inputStream.read(chunk);
                if (read == -1) break;

                // 简化实现：将整个请求读入内存处理
                buffer.write(chunk, 0, read);
            }

            byte[] data = buffer.toByteArray();
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
                        // 提取 name 和 filename
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("name=\"([^\"]*)\"").matcher(line);
                        if (m.find()) name = m.group(1);
                        m = java.util.regex.Pattern.compile("filename=\"([^\"]*)\"").matcher(line);
                        if (m.find()) filename = m.group(1);
                    }
                }

                if ("projectName".equals(name)) {
                    result.projectName = body.trim();
                } else if (name != null && name.startsWith("files") && filename != null && !filename.isEmpty()) {
                    // 去掉末尾的 \r\n
                    String fileContent = body;
                    if (fileContent.endsWith("\r\n")) {
                        fileContent = fileContent.substring(0, fileContent.length() - 2);
                    }
                    if (fileContent.endsWith("\n")) {
                        fileContent = fileContent.substring(0, fileContent.length() - 1);
                    }
                    // 去掉可能的 trailing whitespace
                    byte[] fileBytes = fileContent.getBytes(StandardCharsets.ISO_8859_1);
                    // 去掉最后一个 \r\n（如果有）
                    int trimLen = fileBytes.length;
                    while (trimLen > 0 && (fileBytes[trimLen-1] == '\r' || fileBytes[trimLen-1] == '\n')) {
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

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
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

    // ===================== 新增：创建项目文件夹处理器 =====================
    public static class CreateProjectHandler implements HttpHandler {
        private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9\\-_.]+$");

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // 解析请求体
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root;
            String projectName;
            try {
                root = mapper.readTree(body);
                projectName = root.get("projectName").asText();
            } catch (Exception e) {
                sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"请求格式错误: " + e.getMessage() + "\"}");
                return;
            }

            // 校验项目名
            if (projectName == null || projectName.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"项目名不能为空\"}");
                return;
            }
            projectName = projectName.trim();
            if (!SAFE_NAME.matcher(projectName).matches()) {
                sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"项目名不合法，仅允许字母、数字、- 和 _\"}");
                return;
            }

            // 目标路径：sandbox/{projectName}
            Path projectDir = Paths.get("./sandbox", projectName).normalize();
            Path sandboxRoot = Paths.get("./sandbox").normalize();
            if (!projectDir.startsWith(sandboxRoot)) {
                sendResponse(exchange, 403, "{\"status\":\"error\", \"message\":\"路径非法\"}");
                return;
            }

            // 检查是否已存在
            if (Files.exists(projectDir)) {
                sendResponse(exchange, 409, "{\"status\":\"exists\", \"message\":\"项目已存在: " + projectName + "\"}");
                return;
            }

            // 创建目录
            try {
                Files.createDirectories(projectDir);
                sendResponse(exchange, 200, "{\"status\":\"success\", \"path\":\"sandbox/" + projectName + "\"}");
            } catch (IOException e) {
                sendResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"创建失败: " + e.getMessage() + "\"}");
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ===================== 打开文件夹处理器（支持指定路径） =====================
    public static class OpenFolderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // 解析 path 参数
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);
            String target = params.getOrDefault("path", "sandbox");

            // 安全白名单：只允许打开这三个目录
            Set<String> allowed = Set.of("sandbox", "TestProjects", "HistoryOutput");
            if (!allowed.contains(target)) {
                sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"不支持的目录: " + target + "\"}");
                return;
            }

            Path dirPath = Paths.get("./" + target).toAbsolutePath().normalize();
            File dir = dirPath.toFile();

            if (!dir.exists() || !dir.isDirectory()) {
                sendResponse(exchange, 404, "{\"status\":\"error\", \"message\":\"" + target + " 目录不存在\"}");
                return;
            }

            if (!Desktop.isDesktopSupported()) {
                sendResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"当前系统不支持 Desktop API\"}");
                return;
            }

            try {
                Desktop.getDesktop().open(dir);
                sendResponse(exchange, 200, "{\"status\":\"success\", \"message\":\"已打开 " + target + " 文件夹\"}");
            } catch (IOException e) {
                sendResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"打开失败: " + e.getMessage() + "\"}");
            }
        }

        private Map<String, String> parseQuery(String query) {
            Map<String, String> params = new HashMap<>();
            if (query == null) return params;
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    params.put(kv[0], kv[1]);
                }
            }
            return params;
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ===================== 配置处理器 =====================
    public static class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // 构建配置 JSON
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("maxIterations", AgentConfig.MAX_ITERATIONS);

            String json = new ObjectMapper().writeValueAsString(config);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        }
    }






}
