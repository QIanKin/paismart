package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.model.creator.CreatorPost;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsRefreshService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * creator_get_posts：TTL 感知的「拉博主近 N 条笔记」工具。<br>
 * 策略：
 *  - 先读 DB 缓存；
 *  - 如果 snapshot 比 ttl 还旧，或笔记为空，并且 autoRefresh=true（默认），
 *    就自动触发 {@link XhsRefreshService} 现爬一遍再返回；
 *  - 如果 platform 不是 xhs 或没有 cookie，就退回「返回缓存 + 标注 stale=true」。
 *
 * 这样 Agent 不用自己判断新鲜度，调用一次拿到的数据就是「够新的」。
 */
@Component
public class CreatorGetPostsTool implements Tool {

    private final CreatorService creatorService;
    private final XhsRefreshService xhsRefreshService;
    private final JsonNode schema;

    public CreatorGetPostsTool(CreatorService creatorService, XhsRefreshService xhsRefreshService) {
        this.creatorService = creatorService;
        this.xhsRefreshService = xhsRefreshService;
        this.schema = ToolInputSchemas.object()
                .integerProp("accountId", "博主账号 id（从 creator_search/creator_get 拿）", true)
                .integerProp("limit", "返回最多几条笔记，默认 10，上限 50", false)
                .integerProp("ttlHours", "缓存多少小时内视为新鲜，默认 24", false)
                .booleanProp("autoRefresh", "缓存过期时是否自动刷新，默认 true；false 时直接返回 stale 缓存", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "creator_get_posts"; }

    @Override public String description() {
        return "拉指定博主最近 N 条笔记，自动做 TTL 判定：缓存新鲜直接返回；过期则先触发 xhs_refresh 再返回。"
                + "相比 creator_get，这个工具专注笔记字段并保证数据不过期。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) {
        // 可能触发刷新写入，所以不算 read-only
        return false;
    }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("当前上下文无 orgTag");
        long accountId = input.path("accountId").asLong(-1);
        if (accountId <= 0) return ToolResult.error("accountId 必填");
        int limit = clamp(input.path("limit").asInt(10), 1, 50);
        int ttlHours = clamp(input.path("ttlHours").asInt(24), 1, 24 * 30);
        boolean autoRefresh = !input.hasNonNull("autoRefresh") || input.get("autoRefresh").asBoolean(true);

        CreatorAccount account = creatorService.getAccount(accountId, orgTag).orElse(null);
        if (account == null) return ToolResult.error("账号不存在或跨租户: " + accountId);

        Duration ttl = Duration.ofHours(ttlHours);
        CreatorService.CachedPostsResult cached = creatorService.readPostsWithFreshness(accountId, limit, ttl);

        boolean refreshed = false;
        String refreshErr = null;
        if ((cached.stale() || cached.posts().isEmpty()) && autoRefresh
                && "xhs".equalsIgnoreCase(account.getPlatform())) {
            XhsRefreshService.Result r = xhsRefreshService.refreshAccount(
                    accountId, orgTag, Math.max(limit, 20), false,
                    ctx.sessionId() == null ? "tool-" + accountId : ctx.sessionId(), ctx.cancelled());
            if (r.ok()) {
                refreshed = true;
                cached = creatorService.readPostsWithFreshness(accountId, limit, ttl);
            } else {
                refreshErr = r.errorType() + ":" + r.errorMessage();
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>(cached.posts().size());
        for (CreatorPost p : cached.posts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("title", p.getTitle());
            m.put("postType", p.getPostType());
            m.put("publishedAt", p.getPublishedAt() == null ? null : p.getPublishedAt().toString());
            m.put("likes", p.getLikes());
            m.put("comments", p.getComments());
            m.put("shares", p.getShares());
            m.put("collects", p.getCollects());
            m.put("views", p.getViews());
            m.put("isHit", p.getIsHit());
            m.put("contentText", truncate(p.getContentText(), 600));
            m.put("link", p.getLink());
            rows.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("platform", account.getPlatform());
        out.put("handle", account.getHandle());
        out.put("items", rows);
        out.put("mostRecentSnapshotAt",
                cached.mostRecentSnapshotAt() == null ? null : cached.mostRecentSnapshotAt().toString());
        out.put("stale", cached.stale());
        out.put("refreshed", refreshed);
        if (refreshErr != null) out.put("refreshError", refreshErr);

        String summary = String.format("account #%d 返回 %d 条笔记（stale=%s, refreshed=%s）",
                accountId, rows.size(), cached.stale(), refreshed);
        return ToolResult.of(out, summary);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
