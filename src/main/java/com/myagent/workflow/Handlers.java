package com.myagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
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
    /** 外部目录处理器：处理磁盘上的 TestProjects 文件夹 */
    public static class ExternalFileHandler implements HttpHandler {
        private final Path basePath;

        public ExternalFileHandler(Path basePath) {
            this.basePath = basePath.toAbsolutePath().normalize();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestPath = exchange.getRequestURI().getPath();
            String relative = requestPath.substring("/TestProjects".length());
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
}
