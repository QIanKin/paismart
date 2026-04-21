package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * creator_upsert：Agent/ skill 把"抓到的博主 + 可选的 Creator 人设"写进库。
 * 一次调用同时 upsert 一个账号 + 可选绑定/新建 Creator。重点是幂等：
 *  - 账号按 platform + platformUserId 去重；
 *  - Creator 按 ownerOrgTag + displayName 去重（或传 creatorId 强绑）。
 */
@Component
public class CreatorUpsertTool implements Tool {

    private static final List<String> COOPERATION = Arrays.asList(
            "potential", "negotiating", "active", "paused", "blacklisted");

    private final CreatorService creatorService;
    private final JsonNode schema;

    public CreatorUpsertTool(CreatorService creatorService) {
        this.creatorService = creatorService;
        this.schema = ToolInputSchemas.object()
                .stringProp("platform", "平台：xhs / douyin / bilibili / ... ", true)
                .stringProp("platformUserId", "平台用户 id（去重键）", true)
                .stringProp("handle", "用户名/handle", false)
                .stringProp("displayName", "账号昵称", false)
                .stringProp("avatarUrl", "头像 URL", false)
                .stringProp("bio", "账号简介", false)
                .integerProp("followers", "粉丝数", false)
                .integerProp("likes", "累计点赞", false)
                .integerProp("posts", "发帖数", false)
                .integerProp("avgLikes", "近期均赞", false)
                .integerProp("avgComments", "近期均评论", false)
                .booleanProp("verified", "是否认证", false)
                .stringProp("region", "地区", false)
                .stringProp("homepageUrl", "账号主页", false)
                .stringProp("categoryMain", "主分类", false)
                .stringProp("categorySub", "子分类", false)
                .stringProp("platformTagsJson", "平台标签 JSON 数组", false)
                .stringProp("accountCustomFieldsJson", "账号维度自定义字段 JSON 对象", false)

                .integerProp("creatorId", "可选：绑定到已有 Creator id", false)
                .stringProp("creatorDisplayName", "可选：要创建的 Creator 人设名（首次建档）", false)
                .enumProp("cooperationStatus", "合作状态", COOPERATION, false)
                .stringProp("personaTagsJson", "人设标签 JSON 数组", false)
                .stringProp("trackTagsJson", "赛道 JSON 数组", false)
                .stringProp("priceNote", "报价备注（敏感）", false)
                .stringProp("internalNotes", "内部备注", false)
                .stringProp("creatorCustomFieldsJson", "Creator 维度自定义字段 JSON 对象", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "creator_upsert"; }
    @Override public String description() {
        return "幂等写入一条博主账号 (+ 可选 Creator)。账号按 platform+platformUserId 去重；"
                + "传 creatorId 则绑定到指定 Creator，传 creatorDisplayName 则按 ownerOrgTag+displayName 升级或创建新 Creator。"
                + "skill 抓完后通常通过这个工具入库。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("当前上下文无 orgTag");

        Long creatorId = longVal(input, "creatorId");
        String creatorName = text(input, "creatorDisplayName");

        Creator creator = null;
        if (creatorName != null && !creatorName.isBlank()) {
            CreatorService.CreatorUpsertRequest cr = new CreatorService.CreatorUpsertRequest(
                    creatorId,
                    orgTag,
                    creatorName,
                    null, null, null, null, null,
                    text(input, "personaTagsJson"),
                    text(input, "trackTagsJson"),
                    text(input, "cooperationStatus"),
                    null,
                    text(input, "internalNotes"),
                    text(input, "priceNote"),
                    text(input, "creatorCustomFieldsJson"),
                    ctx.userId());
            creator = creatorService.upsertCreator(cr);
            creatorId = creator.getId();
        }

        CreatorService.CreatorAccountUpsertRequest ar = new CreatorService.CreatorAccountUpsertRequest(
                creatorId,
                orgTag,
                mustText(input, "platform"),
                mustText(input, "platformUserId"),
                text(input, "handle"),
                text(input, "displayName"),
                text(input, "avatarUrl"),
                text(input, "bio"),
                longVal(input, "followers"),
                longVal(input, "following"),
                longVal(input, "likes"),
                longVal(input, "posts"),
                longVal(input, "avgLikes"),
                longVal(input, "avgComments"),
                doubleVal(input, "hitRatio"),
                doubleVal(input, "engagementRate"),
                input.hasNonNull("verified") ? input.get("verified").asBoolean(false) : null,
                text(input, "verifyType"),
                text(input, "region"),
                text(input, "homepageUrl"),
                text(input, "categoryMain"),
                text(input, "categorySub"),
                text(input, "platformTagsJson"),
                text(input, "accountCustomFieldsJson")
        );
        CreatorAccount saved;
        try { saved = creatorService.upsertAccount(ar); }
        catch (IllegalArgumentException e) { return ToolResult.error(e.getMessage()); }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", saved.getId());
        data.put("platform", saved.getPlatform());
        data.put("handle", saved.getHandle());
        data.put("creatorId", saved.getCreatorId());
        return ToolResult.of(data, "upsert account #" + saved.getId()
                + (creator == null ? "" : " → creator #" + creator.getId()));
    }

    private static String text(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asText(null) : null;
    }
    private static String mustText(JsonNode n, String key) {
        String v = text(n, key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(key + " 必填");
        return v;
    }
    private static Long longVal(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asLong() : null;
    }
    private static Double doubleVal(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asDouble() : null;
    }
}
