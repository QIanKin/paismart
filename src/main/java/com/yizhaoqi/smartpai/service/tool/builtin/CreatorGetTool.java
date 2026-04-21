package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.model.creator.CreatorPost;
import com.yizhaoqi.smartpai.repository.creator.CreatorRepository;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * creator_get：取一个账号的详细信息（+ 绑定的 Creator + 近 20 条 post）。
 */
@Component
public class CreatorGetTool implements Tool {

    private final CreatorService creatorService;
    private final CreatorRepository creatorRepository;
    private final JsonNode schema;

    public CreatorGetTool(CreatorService creatorService, CreatorRepository creatorRepository) {
        this.creatorService = creatorService;
        this.creatorRepository = creatorRepository;
        this.schema = ToolInputSchemas.object()
                .integerProp("accountId", "CreatorAccount 的 id（从 creator_search 拿）", true)
                .booleanProp("includePosts", "是否附带最近 20 条帖子，默认 true", false)
                .booleanProp("includeCreator", "是否附带绑定的 Creator（人）详情，默认 true", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "creator_get"; }
    @Override public String description() {
        return "按账号 id 查完整档案：账号指标 + Creator（人）信息 + 近 20 条内容。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("当前上下文无 orgTag");
        long id = input.path("accountId").asLong(-1L);
        if (id <= 0) return ToolResult.error("accountId 必填");
        boolean includePosts = !input.hasNonNull("includePosts") || input.get("includePosts").asBoolean(true);
        boolean includeCreator = !input.hasNonNull("includeCreator") || input.get("includeCreator").asBoolean(true);

        CreatorAccount a = creatorService.getAccount(id, orgTag)
                .orElse(null);
        if (a == null) return ToolResult.error("账号不存在或跨租户：" + id);

        Map<String, Object> account = CreatorSearchTool.summarize(a);
        account.put("bio", a.getBio());
        account.put("homepageUrl", a.getHomepageUrl());
        account.put("platformTagsJson", a.getPlatformTagsJson());
        account.put("customFieldsJson", a.getCustomFieldsJson());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("account", account);

        if (includeCreator && a.getCreatorId() != null) {
            Creator c = creatorRepository.findById(a.getCreatorId()).orElse(null);
            if (c != null && orgTag.equals(c.getOwnerOrgTag())) {
                Map<String, Object> cm = CreatorSearchTool.summarize(c);
                cm.put("internalNotes", c.getInternalNotes());
                cm.put("customFieldsJson", c.getCustomFieldsJson());
                data.put("creator", cm);
            }
        }

        if (includePosts) {
            List<CreatorPost> posts = creatorService.latestPostsOf(id);
            List<Map<String, Object>> rows = new ArrayList<>(posts.size());
            for (CreatorPost p : posts) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getId());
                m.put("postType", p.getPostType());
                m.put("title", p.getTitle());
                m.put("publishedAt", p.getPublishedAt() == null ? null : p.getPublishedAt().toString());
                m.put("likes", p.getLikes());
                m.put("comments", p.getComments());
                m.put("views", p.getViews());
                m.put("isHit", p.getIsHit());
                m.put("link", p.getLink());
                rows.add(m);
            }
            data.put("recentPosts", rows);
        }

        return ToolResult.of(data, "account #" + id + " (" + a.getPlatform() + "/" + a.getHandle() + ")");
    }
}
