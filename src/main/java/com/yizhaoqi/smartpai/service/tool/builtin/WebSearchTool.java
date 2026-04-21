package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.config.WebSearchProperties;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外网关键词搜索。后端 provider 可切换（serper / tavily / bing / duckduckgo / none）。
 * Phase 1 的职责：
 *  - 暴露统一的 name/description/schema；
 *  - 根据 {@link WebSearchProperties} 切到对应 provider 的实现；
 *  - 没配置 key 时，依然返回 ToolResult（isError=true，summary 明示），让 LLM 自行绕开，而不是抛异常。
 */
@Component
public class WebSearchTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebSearchProperties properties;
    private final HttpClient httpClient;
    private final JsonNode schema;

    public WebSearchTool(WebSearchProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.schema = ToolInputSchemas.object()
                .stringProp("query", "搜索关键词（中文/英文皆可）", true)
                .integerProp("top_k", "返回结果数，1~10，默认 5", false)
                .stringProp("locale", "语言偏好，默认 zh-CN", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "web_search"; }

    @Override public String description() {
        return "在公共互联网上用关键词搜索（标题 + 摘要 + URL）。"
                + "当企业内部知识库查不到答案、或者需要获取近期新闻/时效性信息时使用。"
                + "不会直接返回完整正文；需要正文时请再调用 web_fetch。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override public String userFacingName(JsonNode input) { return "联网搜索"; }

    @Override public String summarizeInvocation(JsonNode input) {
        return "web_search: " + (input == null ? "" : input.path("query").asText(""));
    }

    @Override public ToolResult call(ToolContext ctx, JsonNode input) {
        String query = input.path("query").asText("").trim();
        if (query.isEmpty()) return ToolResult.error("query 不能为空");
        int topK = Math.min(10, Math.max(1, input.path("top_k").asInt(5)));
        String locale = input.path("locale").asText("zh-CN");

        String provider = properties.getProvider() == null ? "none" : properties.getProvider().toLowerCase();
        try {
            return switch (provider) {
                case "serper" -> searchSerper(query, topK, locale);
                case "tavily" -> searchTavily(query, topK);
                case "bing" -> searchBing(query, topK, locale);
                default -> ToolResult.error("web_search 未配置 provider（application.yml 里 web-search.provider=none）。"
                        + "请让用户在企业知识库中上传资料，或由管理员配置 web-search。");
            };
        } catch (Exception e) {
            logger.error("web_search provider={} 调用失败", provider, e);
            return ToolResult.error("web_search 调用失败: " + e.getMessage());
        }
    }

    // ---------------- Provider: serper.dev ----------------

    private ToolResult searchSerper(String query, int topK, String locale) throws Exception {
        if (isBlank(properties.getApiKey())) {
            return ToolResult.error("web_search(serper) 未配置 api-key");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("q", query);
        body.put("num", topK);
        if (locale.startsWith("zh")) {
            body.put("gl", "cn"); body.put("hl", "zh-cn");
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://google.serper.dev/search"))
                .timeout(TIMEOUT)
                .header("X-API-KEY", properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            return ToolResult.error("serper HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
        }
        JsonNode root = MAPPER.readTree(resp.body());
        return toResult(query, topK, root.path("organic"));
    }

    // ---------------- Provider: tavily ----------------

    private ToolResult searchTavily(String query, int topK) throws Exception {
        if (isBlank(properties.getApiKey())) {
            return ToolResult.error("web_search(tavily) 未配置 api-key");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("api_key", properties.getApiKey());
        body.put("query", query);
        body.put("max_results", topK);
        body.put("search_depth", "basic");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            return ToolResult.error("tavily HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode results = root.path("results");
        List<Map<String, Object>> items = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode n : results) {
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("title", n.path("title").asText(""));
                it.put("url", n.path("url").asText(""));
                it.put("snippet", n.path("content").asText(""));
                items.add(it);
            }
        }
        return toResultRaw(query, topK, items);
    }

    // ---------------- Provider: Bing Web Search ----------------

    private ToolResult searchBing(String query, int topK, String locale) throws Exception {
        if (isBlank(properties.getApiKey())) {
            return ToolResult.error("web_search(bing) 未配置 api-key");
        }
        String url = "https://api.bing.microsoft.com/v7.0/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=" + topK
                + "&mkt=" + URLEncoder.encode(locale, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Ocp-Apim-Subscription-Key", properties.getApiKey())
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            return ToolResult.error("bing HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode values = root.path("webPages").path("value");
        List<Map<String, Object>> items = new ArrayList<>();
        if (values.isArray()) {
            for (JsonNode n : values) {
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("title", n.path("name").asText(""));
                it.put("url", n.path("url").asText(""));
                it.put("snippet", n.path("snippet").asText(""));
                items.add(it);
            }
        }
        return toResultRaw(query, topK, items);
    }

    // ---------------- 转换辅助 ----------------

    private ToolResult toResult(String query, int topK, JsonNode organic) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (organic != null && organic.isArray()) {
            for (JsonNode n : organic) {
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("title", n.path("title").asText(""));
                it.put("url", n.path("link").asText(""));
                it.put("snippet", n.path("snippet").asText(""));
                items.add(it);
            }
        }
        return toResultRaw(query, topK, items);
    }

    private ToolResult toResultRaw(String query, int topK, List<Map<String, Object>> items) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("total", items.size());
        data.put("results", items);
        String summary = items.isEmpty() ? "未搜到结果" : "搜到 " + items.size() + " 条结果";
        return ToolResult.of(data, summary);
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
