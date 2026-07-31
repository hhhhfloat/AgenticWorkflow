package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class ProjectsHandler implements HttpHandler {
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

