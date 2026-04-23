package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.repository.creator.CreatorRepository;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolErrors;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.PgyRoleProbe;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import com.yizhaoqi.smartpai.service.xhs.XhsSkillRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code xhs_pgy_kol_detail}：用蒲公英后台接口拉取单个小红书博主的完整画像。
 *
 * <p>包含：基本 summary（粉丝/互动率/报价）+ 粉丝画像 + 历史趋势 + 笔记表现。
 * 适合 agent 在博主选人 / 报价决策 / 截图里显示 "(??)" 时用来补齐数据。
 *
 * <h3>输入二选一</h3>
 * <ul>
 *   <li>{@code accountId}：博主库 creator_accounts.id（会从里面拿 platformUserId）</li>
 *   <li>{@code userId}：直接给蒲公英 user_id（字符串）</li>
 * </ul>
 *
 * <h3>可选回写</h3>
 * <ul>
 *   <li>{@code persistMetrics=true}（默认 true）：把 followers / avgLikes / engagementRate 写回 CreatorAccount</li>
 *   <li>{@code persistPriceNote=true}（默认 true）：如果该 account 有绑定 Creator（人），把 priceNote 写回 Creator</li>
 * </ul>
 */
@Component
public class XhsPgyKolDetailTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(XhsPgyKolDetailTool.class);

    private final XhsSkillRunner runner;
    private final CreatorService creatorService;
    private final CreatorRepository creatorRepository;
    private final PgyRoleProbe roleProbe;
    private final XhsCookieService cookieService;
    private final JsonNode schema;

    public XhsPgyKolDetailTool(XhsSkillRunner runner,
                               CreatorService creatorService,
                               CreatorRepository creatorRepository,
                               PgyRoleProbe roleProbe,
                               XhsCookieService cookieService) {
        this.runner = runner;
        this.creatorService = creatorService;
        this.creatorRepository = creatorRepository;
        this.roleProbe = roleProbe;
        this.cookieService = cookieService;
        this.schema = ToolInputSchemas.object()
                .integerProp("accountId", "博主库里的 creator_accounts.id（platform=xhs）。与 userId 二选一。", false)
                .stringProp("userId", "蒲公英 KOL 的 userId（字符串）。与 accountId 二选一。", false)
                .booleanProp("persistMetrics",
                        "true=把 followers / avgLikes / engagementRate 写回 CreatorAccount（默认 true，需要 accountId 已在库）。",
                        false)
                .booleanProp("persistPriceNote",
                        "true=把报价字符串写回绑定的 Creator（人）的 priceNote 字段（默认 true）。",
                        false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_pgy_kol_detail"; }

    @Override public String description() {
        return "通过蒲公英后台拉单个小红书博主的完整画像：基本数据（粉丝/互动率/报价 priceInfoList）+ 粉丝画像 "
                + "+ 历史增长 + 笔记表现。需要 xhs_pgy cookie。"
                + "默认会把关键指标（followers / avgLikes / engagementRate）和报价 priceNote 回写博主库，"
                + "让下次 creator_get 看到最新数据，解决「内部库报价为 (??)」的幻觉问题。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) {
        // 默认是写入博主库的；只有两个 persist 都显式 false 才是只读
        boolean pm = !input.hasNonNull("persistMetrics") || input.path("persistMetrics").asBoolean(true);
        boolean pn = !input.hasNonNull("persistPriceNote") || input.path("persistPriceNote").asBoolean(true);
        return !(pm || pn);
    }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) {
            return ToolResult.error(ToolErrors.BAD_REQUEST, "当前上下文缺少 orgTag");
        }
        long accountId = input.path("accountId").asLong(-1L);
        String userIdParam = input.path("userId").asText("").trim();

        CreatorAccount account = null;
        String userId = null;
        if (accountId > 0) {
            account = creatorService.getAccount(accountId, orgTag).orElse(null);
            if (account == null) {
                return ToolResult.error(ToolErrors.NOT_FOUND, "accountId #" + accountId + " 不存在或跨租户");
            }
            userId = account.getPlatformUserId();
            if (userId == null || userId.isBlank()) {
                return ToolResult.error(ToolErrors.BAD_REQUEST,
                        "account #" + accountId + " 无 platformUserId，无法调蒲公英详情（可能是手工录入的记录）");
            }
        } else if (!userIdParam.isBlank()) {
            userId = userIdParam;
        } else {
            return ToolResult.error(ToolErrors.BAD_REQUEST, "accountId 与 userId 至少提供一个");
        }

        boolean persistMetrics = !input.hasNonNull("persistMetrics") || input.path("persistMetrics").asBoolean(true);
        boolean persistPriceNote = !input.hasNonNull("persistPriceNote") || input.path("persistPriceNote").asBoolean(true);

        // 预检：Spider_XHS 的蒲公英 API 只对品牌主/机构有效，提前探测避免白跑脚本。
        var picked = cookieService.pickAvailable(orgTag, "xhs_pgy");
        if (picked.isEmpty()) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorType", "no_cookie");
            return ToolResult.error(ToolErrors.NO_TARGET,
                    "当前 org 下没有 ACTIVE 的 xhs_pgy cookie。请扫码/录入一个'蒲公英品牌主/机构'账号。",
                    extra);
        }
        PgyRoleProbe.Result probe = roleProbe.probe(picked.get().cookie());
        if (!probe.reachable()) {
            cookieService.reportFailure(picked.get().cookieId(),
                    "pgy preflight unreachable: " + probe.reason());
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorType", "pgy_unreachable");
            extra.put("role", probe.role());
            extra.put("httpStatus", probe.httpStatus());
            return ToolResult.error("pgy_unreachable",
                    "蒲公英预检失败：" + probe.reason()
                            + "（cookie 可能已过期或被风控，建议重新扫码登录品牌主账号）。",
                    extra);
        }
        if (!probe.brandQualified()) {
            cookieService.reportSuccess(picked.get().cookieId()); // 不冤杀 cookie
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorType", "not_brand_account");
            extra.put("role", probe.role());
            extra.put("nickName", probe.nickName());
            extra.put("userId", probe.userId());
            return ToolResult.error("not_brand_account",
                    "当前 xhs_pgy cookie 登录的账号 [" + probe.nickName() + "] 角色是 "
                            + probe.role() + "（非品牌主/机构）。Spider_XHS 的蒲公英详情接口"
                            + "只对'蒲公英品牌主/机构'账号有效，KOL/个人账号调不通。"
                            + "请换一个已开通品牌主资质的账号。",
                    extra);
        }
        cookieService.reportSuccess(picked.get().cookieId());

        // --- 调 skill ---
        XhsSkillRunner.RunRequest req = new XhsSkillRunner.RunRequest();
        req.orgTag = orgTag;
        req.sessionId = ctx.sessionId();
        req.skillName = "xhs-pgy-kol-detail";
        req.scriptRelative = "scripts/pgy_kol_detail.py";
        req.cookiePlatform = "xhs_pgy";
        req.extraArgs.add("--user-id");
        req.extraArgs.add(userId);
        req.timeoutSeconds = 180;
        req.cancelled = ctx.cancelled();

        XhsSkillRunner.RunResult res = runner.run(req);
        if (!res.ok()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("errorType", res.errorType());
            return ToolResult.error("xhs-pgy-kol-detail 执行失败: " + res.errorMessage(), detail);
        }
        JsonNode payload = res.payload();
        JsonNode snapshot = payload.path("snapshot");

        // --- 取关键字段 ---
        Long followers = longOrNull(snapshot.get("followers"));
        Long avgLikes = longOrNull(snapshot.get("avgLikes"));
        Long avgComments = longOrNull(snapshot.get("avgComments"));
        Double engagementRate = doubleOrNull(snapshot.get("engagementRate"));
        String priceNote = textOrNull(snapshot.get("priceNote"));

        boolean metricsSaved = false;
        boolean priceSaved = false;
        String priceTarget = null;

        // --- 回写 account 指标 ---
        if (persistMetrics && account != null
                && (followers != null || avgLikes != null || avgComments != null || engagementRate != null)) {
            try {
                CreatorService.CreatorAccountUpsertRequest up = new CreatorService.CreatorAccountUpsertRequest(
                        account.getCreatorId(),
                        orgTag,
                        account.getPlatform(),
                        account.getPlatformUserId(),
                        account.getHandle(),
                        account.getDisplayName(),
                        account.getAvatarUrl(),
                        account.getBio(),
                        followers != null ? followers : account.getFollowers(),
                        account.getFollowing(),
                        account.getLikes(),
                        account.getPosts(),
                        avgLikes != null ? avgLikes : account.getAvgLikes(),
                        avgComments != null ? avgComments : account.getAvgComments(),
                        account.getHitRatio(),
                        engagementRate != null ? engagementRate : account.getEngagementRate(),
                        account.getVerified(),
                        account.getVerifyType(),
                        account.getRegion(),
                        account.getHomepageUrl(),
                        account.getCategoryMain(),
                        account.getCategorySub(),
                        account.getPlatformTagsJson(),
                        account.getCustomFieldsJson()
                );
                creatorService.upsertAccount(up);
                metricsSaved = true;
            } catch (Exception e) {
                log.warn("[xhs_pgy_kol_detail] 回写 account 指标失败 account={} err={}", accountId, e.getMessage());
            }
        }

        // --- 回写 creator priceNote ---
        if (persistPriceNote && priceNote != null && !priceNote.isBlank() && account != null
                && account.getCreatorId() != null) {
            try {
                Creator creator = creatorRepository.findById(account.getCreatorId())
                        .filter(c -> orgTag.equals(c.getOwnerOrgTag()))
                        .orElse(null);
                if (creator != null) {
                    CreatorService.CreatorUpsertRequest upsert = new CreatorService.CreatorUpsertRequest(
                            creator.getId(),
                            orgTag,
                            creator.getDisplayName(),
                            creator.getRealName(),
                            creator.getGender(),
                            creator.getBirthYear(),
                            creator.getCity(),
                            creator.getCountry(),
                            creator.getPersonaTagsJson(),
                            creator.getTrackTagsJson(),
                            creator.getCooperationStatus(),
                            creator.getInternalOwnerId(),
                            creator.getInternalNotes(),
                            priceNote,
                            creator.getCustomFieldsJson(),
                            "xhs_pgy_kol_detail"
                    );
                    creatorService.upsertCreator(upsert);
                    priceSaved = true;
                    priceTarget = "creator#" + creator.getId();
                }
            } catch (Exception e) {
                log.warn("[xhs_pgy_kol_detail] 回写 priceNote 失败 account={} err={}", accountId, e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        if (account != null) out.put("accountId", account.getId());
        out.put("snapshot", snapshot);
        out.put("priceNote", priceNote);
        out.put("summary", payload.path("summary"));
        out.put("fansPortrait", payload.path("fansPortrait"));
        out.put("fansHistory", payload.path("fansHistory"));
        out.put("notesRate", payload.path("notesRate"));
        out.put("persistMetrics", metricsSaved);
        out.put("persistPriceNote", priceSaved);
        if (priceTarget != null) out.put("priceNoteWrittenTo", priceTarget);

        StringBuilder sum = new StringBuilder("xhs_pgy_kol_detail user=").append(userId);
        if (followers != null) sum.append(" followers=").append(followers);
        if (priceNote != null) sum.append(" price=").append(priceNote);
        sum.append(" | saved: metrics=").append(metricsSaved).append(" price=").append(priceSaved);
        return ToolResult.of(out, sum.toString());
    }

    private static Long longOrNull(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        return n.asLong();
    }
    private static Double doubleOrNull(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        return n.asDouble();
    }
    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        String s = n.asText();
        return (s == null || s.isBlank()) ? null : s;
    }
}
