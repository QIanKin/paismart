package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.repository.creator.CreatorAccountRepository;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.TikhubXhsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * tikhub_creator_import：按博主名 / 小红书号 / 主页链接走 TikHub 搜索并入库。
 *
 * <p>这是普通公开博主链路的主入口，不依赖 cookie。蒲公英仍只负责画像/报价，聚光仍只负责广告。
 */
@Component
public class TikhubCreatorImportTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TikhubXhsService tikhubService;
    private final CreatorService creatorService;
    private final CreatorAccountRepository accountRepository;
    private final JsonNode schema;

    public TikhubCreatorImportTool(TikhubXhsService tikhubService,
                                   CreatorService creatorService,
                                   CreatorAccountRepository accountRepository) {
        this.tikhubService = tikhubService;
        this.creatorService = creatorService;
        this.accountRepository = accountRepository;
        this.schema = ToolInputSchemas.object()
                .arrayProp("keywords", "博主昵称、小红书号或主页 URL 数组；可一次传多个",
                        ToolInputSchemas.stringType(), true)
                .integerProp("maxResultsPerKeyword", "每个关键词最多取几个候选，默认 5，最大 20", false)
                .booleanProp("dryRun", "true 只预览不入库；false 自动写入博主库，默认 false", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "tikhub_creator_import"; }

    @Override public String description() {
        return "通过 TikHub 按博主昵称/小红书号/主页链接搜索小红书公开账号，并自动 upsert 到博主库。"
                + "普通公开资料优先走这个工具；蒲公英只保留画像/报价，聚光只保留广告数据。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return input != null && input.path("dryRun").asBoolean(false); }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("bad_request", "当前上下文缺少 orgTag");
        if (!tikhubService.configured()) {
            return ToolResult.error("provider_not_configured",
                    "TikHub 未配置：请设置 XHS_TIKHUB_ENABLED=true 与 XHS_TIKHUB_API_KEY");
        }

        List<String> keywords = new ArrayList<>();
        JsonNode arr = input.path("keywords");
        if (arr.isArray()) {
            arr.forEach(n -> {
                String value = n.asText("").trim();
                if (!value.isBlank() && !keywords.contains(value)) keywords.add(value);
            });
        }
        if (keywords.isEmpty()) return ToolResult.error("bad_input", "keywords 至少传一个");

        boolean dryRun = input.path("dryRun").asBoolean(false);
        int maxResultsPerKeyword = Math.max(1, Math.min(input.path("maxResultsPerKeyword").asInt(5), 20));

        List<Map<String, Object>> items = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        int localHits = 0;
        String providerError = null;

        for (String keyword : keywords) {
            var localPage = creatorService.searchAccounts(
                    new CreatorService.AccountSearchQuery(orgTag, "xhs", keyword, null,
                            null, null, null, null, null),
                    CreatorService.defaultPageable(0, maxResultsPerKeyword, "followers:desc"));
            if (!localPage.isEmpty()) {
                for (CreatorAccount account : localPage.getContent()) {
                    items.add(toLocalHit(account, keyword));
                    localHits++;
                }
                continue;
            }

            TikhubXhsService.UserSearchResult search;
            try {
                search = tikhubService.searchUsers(keyword, 1);
            } catch (TikhubXhsService.ApiException e) {
                providerError = e.getMessage();
                skipped++;
                items.add(Map.of(
                        "keyword", keyword,
                        "status", "provider_failed",
                        "error", e.getMessage()
                ));
                continue;
            }
            int count = 0;
            for (TikhubXhsService.UserSummary user : search.users) {
                if (count >= maxResultsPerKeyword) break;
                count++;
                Map<String, Object> row = toPreview(user, keyword);
                if (!dryRun) {
                    boolean exists = accountRepository.findByPlatformAndPlatformUserId("xhs", user.userId).isPresent();
                    try {
                        TikhubXhsService.UserProfile profile = tikhubService.fetchUserInfo(user.userId);
                        TikhubXhsService.UserNotesResult notes = tikhubService.fetchUserNotes(user.userId, "", 1);
                        TikhubXhsService.UserNote latestNote = notes.notes == null || notes.notes.isEmpty()
                                ? null : notes.notes.get(0);
                        CreatorService.CreatorAccountUpsertRequest req = new CreatorService.CreatorAccountUpsertRequest(
                                null,
                                orgTag,
                                "xhs",
                                user.userId,
                                profile.redId == null || profile.redId.isBlank() ? user.redId : profile.redId,
                                profile.nickname == null || profile.nickname.isBlank() ? user.nickname : profile.nickname,
                                profile.avatar == null || profile.avatar.isBlank() ? user.avatar : profile.avatar,
                                profile.desc,
                                parseHumanCount(profile.fansText),
                                parseHumanCount(profile.followingText),
                                parseHumanCount(profile.likesText),
                                profile.noteCount,
                                null,
                                null,
                                null,
                                null,
                                profile.verified,
                                profile.verified ? "verified" : null,
                                profile.ipLocation,
                                "https://www.xiaohongshu.com/user/profile/" + user.userId,
                                null,
                                null,
                                null,
                                toJson(buildCustomFields(keyword, profile, latestNote))
                        );
                        CreatorAccount saved = creatorService.upsertAccount(req);
                        row.put("accountId", saved.getId());
                        row.put("platformUserId", saved.getPlatformUserId());
                        if (latestNote != null) {
                            row.put("latestNoteId", latestNote.noteId);
                            row.put("latestNoteTitle", latestNote.title);
                            row.put("latestNoteUrl", latestNote.link);
                        }
                        row.put("status", exists ? "updated" : "inserted");
                        if (exists) updated++; else inserted++;
                    } catch (Exception e) {
                        skipped++;
                        row.put("status", "skipped");
                        row.put("error", e.getMessage());
                    }
                }
                items.add(row);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keywords", keywords);
        data.put("dryRun", dryRun);
        data.put("items", items);
        if (!dryRun) {
            data.put("inserted", inserted);
            data.put("updated", updated);
            data.put("skipped", skipped);
        }
        data.put("localHits", localHits);
        if (providerError != null) data.put("providerError", providerError);

        if (items.isEmpty() && providerError != null) {
            return ToolResult.error("provider_failed",
                    "TikHub 当前无法按关键词搜索用户，请优先使用库内已有账号，或改传主页链接/精确小红书号后再试。",
                    data);
        }
        return ToolResult.of(data, dryRun
                ? String.format("TikHub 预览到 %d 个博主候选，未入库", items.size())
                : String.format("TikHub 导入 %d 个候选 → +%d 新增 / %d 更新 / %d 跳过",
                items.size(), inserted, updated, skipped));
    }

    private static Map<String, Object> toPreview(TikhubXhsService.UserSummary user, String keyword) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", keyword);
        out.put("userId", user.userId);
        out.put("redId", user.redId);
        out.put("nickname", user.nickname);
        out.put("fansText", user.fansText);
        out.put("noteCount", user.noteCount);
        out.put("avatar", user.avatar);
        out.put("homepageUrl", "https://www.xiaohongshu.com/user/profile/" + user.userId);
        return out;
    }

    private static Map<String, Object> toLocalHit(CreatorAccount account, String keyword) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", keyword);
        out.put("accountId", account.getId());
        out.put("platformUserId", account.getPlatformUserId());
        out.put("redId", account.getHandle());
        out.put("nickname", account.getDisplayName());
        out.put("fansText", account.getFollowers());
        out.put("noteCount", account.getPosts());
        out.put("homepageUrl", account.getHomepageUrl());
        out.put("status", "local_hit");
        return out;
    }

    private static Map<String, Object> buildCustomFields(String keyword,
                                                         TikhubXhsService.UserProfile profile,
                                                         TikhubXhsService.UserNote latestNote) {
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("xhsSource", "tikhub");
        custom.put("tikhubMatchedKeyword", keyword);
        custom.put("tikhubSyncedAt", java.time.Instant.now().toString());
        putIfPresent(custom, "redId", profile == null ? null : profile.redId);
        putIfPresent(custom, "xhsUserId", profile == null ? null : profile.userId);
        putIfPresent(custom, "xhsNickname", profile == null ? null : profile.nickname);
        if (latestNote != null) {
            putIfPresent(custom, "latestNoteId", latestNote.noteId);
            putIfPresent(custom, "latestNoteTitle", latestNote.title);
            putIfPresent(custom, "latestNoteUrl", latestNote.link);
            putIfPresent(custom, "latestNoteType", latestNote.type);
        }
        if (profile != null && profile.rawMap != null && !profile.rawMap.isEmpty()) {
            Object official = profile.rawMap.get("redOfficialVerifyType");
            if (official != null) custom.put("redOfficialVerifyType", official);
        }
        return custom;
    }

    private static void putIfPresent(Map<String, Object> custom, String key, Object value) {
        if (value == null) return;
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) custom.put(key, value);
    }

    private static String toJson(Object value) {
        if (value == null) return null;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseHumanCount(String text) {
        if (text == null || text.isBlank()) return null;
        String raw = text.trim().replace(",", "");
        try {
            return Long.parseLong(raw);
        } catch (Exception ignored) {
            // continue
        }
        try {
            if (raw.endsWith("万")) return Math.round(Double.parseDouble(raw.substring(0, raw.length() - 1)) * 10_000d);
            if (raw.endsWith("千")) return Math.round(Double.parseDouble(raw.substring(0, raw.length() - 1)) * 1_000d);
        } catch (Exception ignored) {
            // continue
        }
        return null;
    }
}
