package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 小型外部 JSON API 客户端，供 TikHub / 第三方 XHS provider 等公开 API 调用共用。
 */
final class ExternalJsonApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient;

    ExternalJsonApiClient(int timeoutSeconds) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    ApiResponse get(String baseUrl, String path, Map<String, Object> query,
                    String apiKeyHeader, String apiKey, int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(joinUrl(baseUrl, path) + queryString(query)))
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .header("Accept", "application/json")
                .GET();
        addAuth(builder, apiKeyHeader, apiKey);
        return send(builder.build());
    }

    ApiResponse postJson(String baseUrl, String path, Map<String, Object> body,
                         String apiKeyHeader, String apiKey, int timeoutSeconds) throws Exception {
        String json = MAPPER.writeValueAsString(body == null ? Map.of() : body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(joinUrl(baseUrl, path)))
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        addAuth(builder, apiKeyHeader, apiKey);
        return send(builder.build());
    }

    private ApiResponse send(HttpRequest request) throws Exception {
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = null;
        try {
            json = MAPPER.readTree(resp.body());
        } catch (Exception ignored) {
            // provider 偶尔返回纯文本错误；保留 rawBody 给工具层展示。
        }
        return new ApiResponse(resp.statusCode(), json, resp.body());
    }

    private static void addAuth(HttpRequest.Builder builder, String header, String key) {
        if (key == null || key.isBlank()) return;
        String h = header == null || header.isBlank() ? "X-API-Key" : header.trim();
        String value = "Authorization".equalsIgnoreCase(h) && !key.regionMatches(true, 0, "Bearer ", 0, 7)
                ? "Bearer " + key
                : key;
        builder.header(h, value);
    }

    private static String joinUrl(String baseUrl, String path) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        String p = path == null || path.isBlank() ? "/" : path.trim();
        if (b.endsWith("/") && p.startsWith("/")) return b.substring(0, b.length() - 1) + p;
        if (!b.endsWith("/") && !p.startsWith("/")) return b + "/" + p;
        return b + p;
    }

    private static String queryString(Map<String, Object> query) {
        if (query == null || query.isEmpty()) return "";
        StringJoiner sj = new StringJoiner("&", "?", "");
        for (Map.Entry<String, Object> e : query.entrySet()) {
            if (e.getValue() == null) continue;
            sj.add(enc(e.getKey()) + "=" + enc(String.valueOf(e.getValue())));
        }
        String out = sj.toString();
        return "?".equals(out) ? "" : out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    static Map<String, Object> envelope(ApiResponse resp) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", resp.status());
        if (resp.json() != null) data.put("json", resp.json());
        else data.put("rawBody", resp.rawBody());
        return data;
    }

    record ApiResponse(int status, JsonNode json, String rawBody) {}
}
