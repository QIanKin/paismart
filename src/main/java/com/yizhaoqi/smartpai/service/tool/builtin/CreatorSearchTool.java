package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * creator_search：按平台/关键词/粉丝区间等条件在本租户下分页检索博主账号。
 * 只做账号维度的搜索（用户指定的 flat schema）；对应的 Creator（人）可通过 creator_get(id=, fetchCreator=true) 进一步拿。
 */
@Component
public class CreatorSearchTool implements Tool {

    private final CreatorService creatorService;
    private final JsonNode schema;

    public CreatorSearchTool(CreatorService creatorService) {
        this.creatorService = creatorService;
        this.schema = ToolInputSchemas.object()
                .stringProp("platform", "平台：xhs / douyin / bilibili / weibo / wechat_mp 等", false)
                .stringProp("keyword", "在 displayName/handle/bio 里模糊匹配", false)
                .stringProp("categoryMain", "平台主分类精确匹配", false)
                .integerProp("followersMin", "粉丝下限", false)
                .integerProp("followersMax", "粉丝上限", false)
                .booleanProp("verifiedOnly", "仅返回已认证账号", false)
                .integerProp("creatorId", "只看绑定到某个 Creator 的账号", false)
                .stringProp("tagContains", "平台标签模糊包含", false)
                .integerProp("page", "页码（0 起），默认 0", false)
                .integerProp("size", "每页条数，默认 20，最大 200", false)
                .stringProp("sort", "排序 key，如 'followers:desc' / 'updatedAt:desc' / 'avgLikes:desc'", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "creator_search"; }
    @Override public String description() {
        return "在博主数据库里按条件分页检索账号。支持粉丝区间、分类、关键词、标签等过滤。"
                + "返回账号核心指标（粉丝/均赞/爆款率）+ 分页元信息。用于快速挑选候选博主。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("当前上下文无 orgTag，无法检索");

        CreatorService.AccountSearchQuery q = new CreatorService.AccountSearchQuery(
                orgTag,
                text(input, "platform"),
                text(input, "keyword"),
                text(input, "categoryMain"),
                longVal(input, "followersMin"),
                longVal(input, "followersMax"),
                input.has("verifiedOnly") ? input.get("verifiedOnly").asBoolean(false) : null,
                longVal(input, "creatorId"),
                text(input, "tagContains"));

        int page = input.has("page") ? input.get("page").asInt(0) : 0;
        int size = input.has("size") ? input.get("size").asInt(20) : 20;
        String sort = text(input, "sort");
        Pageable pageable = CreatorService.defaultPageable(page, size,
                sort == null ? "id:desc" : sort);

        Page<CreatorAccount> res = creatorService.searchAccounts(q, pageable);
        List<Map<String, Object>> rows = new ArrayList<>(res.getContent().size());
        for (CreatorAccount a : res.getContent()) rows.add(summarize(a));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", res.getTotalElements());
        data.put("page", res.getNumber());
        data.put("size", res.getSize());
        data.put("items", rows);
        return ToolResult.of(data, String.format(Locale.ROOT,
                "找到 %d 条，当前第 %d 页（%d 条）", res.getTotalElements(), res.getNumber(), rows.size()));
    }

    static Map<String, Object> summarize(CreatorAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("platform", a.getPlatform());
        m.put("handle", a.getHandle());
        m.put("displayName", a.getDisplayName());
        m.put("followers", a.getFollowers());
        m.put("avgLikes", a.getAvgLikes());
        m.put("hitRatio", a.getHitRatio());
        m.put("engagementRate", a.getEngagementRate());
        m.put("categoryMain", a.getCategoryMain());
        m.put("verified", a.getVerified());
        m.put("creatorId", a.getCreatorId());
        m.put("latestSnapshotAt", a.getLatestSnapshotAt() == null ? null : a.getLatestSnapshotAt().toString());
        return m;
    }

    static Map<String, Object> summarize(Creator c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("displayName", c.getDisplayName());
        m.put("realName", c.getRealName());
        m.put("cooperationStatus", c.getCooperationStatus());
        m.put("personaTagsJson", c.getPersonaTagsJson());
        m.put("trackTagsJson", c.getTrackTagsJson());
        m.put("priceNote", c.getPriceNote());
        return m;
    }

    private static String text(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asText(null) : null;
    }

    private static Long longVal(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asLong() : null;
    }
}
