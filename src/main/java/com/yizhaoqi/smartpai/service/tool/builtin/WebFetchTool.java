package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 拉取一个公网 URL 的正文文本。给 Agent 用于：
 *  - 搜索结果点进去看详情
 *  - 用户贴一个链接让 Agent 总结 / 抽取字段
 *  - skill 调用期间作为辅助 ——（注：skill 调用更大的网络操作通过脚本自己做）
 *
 * 安全策略：
 *  - 只允许 http/https；
 *  - 禁止访问内网 IP（127.0.0.1 / 10.x / 172.16~31.x / 192.168.x / ::1 / 169.254.x / 0.0.0.0）；
 *  - 超时 15s；最大响应体 2MB；
 *  - 明文返回"正文文本"（Jsoup 简化），不返回原始 HTML，避免 token 爆炸。
 */
@Component
public class WebFetchTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(WebFetchTool.class);
    private static final long MAX_BODY_BYTES = 2L * 1024 * 1024;
    private static final int MAX_TEXT_LENGTH = 8000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final JsonNode schema;

    public WebFetchTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.schema = ToolInputSchemas.object()
                .stringProp("url", "要抓取的绝对 URL（http/https）", true)
                .booleanProp("raw", "是否返回原始 HTML；默认 false，只返回抽取后的正文文本", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "web_fetch"; }

    @Override public String description() {
        return "抓取指定 URL 的正文文本（默认 HTML → 纯文本抽取）。用于查看搜索结果的详细内容、"
                + "总结网页文章、从用户贴出的链接中提取信息。"
                + "禁止访问内网地址；单次响应最多 2MB，纯文本截断到 8000 字符。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return true; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override public String userFacingName(JsonNode input) { return "拉取网页"; }

    @Override public String summarizeInvocation(JsonNode input) {
        return input == null ? "web_fetch" : "web_fetch: " + input.path("url").asText("");
    }

    @Override public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        String url = input.path("url").asText("");
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return PermissionResult.deny("只允许 http/https 协议");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return PermissionResult.deny("URL host 为空");
            }
            if (isInternalHost(host)) {
                return PermissionResult.deny("禁止访问内网/回环地址");
            }
        } catch (IllegalArgumentException ex) {
            return PermissionResult.deny("URL 非法: " + ex.getMessage());
        }
        return PermissionResult.allow();
    }

    @Override public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String url = input.path("url").asText("");
        boolean raw = input.path("raw").asBoolean(false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "PaiSmartAgent/1.0")
                .GET()
                .build();

        HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = resp.statusCode();
        byte[] body = resp.body();
        if (body.length > MAX_BODY_BYTES) {
            return ToolResult.error("响应体过大 (>2MB)，拒绝处理");
        }
        String charset = detectCharset(resp, body);
        String html = new String(body, java.nio.charset.Charset.forName(charset));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("status", status);
        data.put("contentType", resp.headers().firstValue("content-type").orElse(""));
        data.put("length", body.length);

        if (raw) {
            data.put("html", trimTo(html, MAX_TEXT_LENGTH));
        } else {
            try {
                Document doc = Jsoup.parse(html, url);
                String title = doc.title();
                String text = doc.body() != null ? doc.body().text() : doc.text();
                data.put("title", title);
                data.put("text", trimTo(text, MAX_TEXT_LENGTH));
            } catch (Exception parseEx) {
                data.put("text", trimTo(html, MAX_TEXT_LENGTH));
                data.put("parse_error", parseEx.getMessage());
            }
        }

        String summary = "GET " + url + " → " + status + " (" + body.length + "B)";
        return ToolResult.of(data, summary);
    }

    private static String detectCharset(HttpResponse<?> resp, byte[] body) {
        String contentType = resp.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        int idx = contentType.indexOf("charset=");
        if (idx >= 0) {
            String cs = contentType.substring(idx + 8).split("[;\\s]")[0].trim();
            if (!cs.isEmpty()) return cs;
        }
        // 粗略 meta 探测
        String head = new String(body, 0, Math.min(body.length, 2048), java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        int metaIdx = head.indexOf("charset=");
        if (metaIdx >= 0) {
            String cs = head.substring(metaIdx + 8).split("[\"'\\s/>]")[0].trim();
            if (!cs.isEmpty()) return cs;
        }
        return "UTF-8";
    }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static boolean isInternalHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("0.0.0.0")) return true;
        try {
            InetAddress addr = InetAddress.getByName(h);
            return addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress() || addr.isAnyLocalAddress();
        } catch (Exception e) {
            logger.debug("内网探测失败 host={} err={}", h, e.getMessage());
            return false;
        }
    }
}
