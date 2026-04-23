package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.repository.creator.CreatorAccountRepository;
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
import java.util.List;
import java.util.Map;

/**
 * xhs_fetch_pgy_kol：调用 '蒲公英' 后台接口拉取 KOL 列表（带粉丝数、均赞、报价区间等）。
 *
 * 支持两种 sink：
 *  - dryRun=true（默认）只返回 KOLs，让 LLM 自己看；
 *  - dryRun=false 把每个 KOL upsert 成 CreatorAccount（platform=xhs），返回 +N 新增 / +M 更新。
 */
@Component
public class XhsFetchPgyKolTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(XhsFetchPgyKolTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final XhsSkillRunner runner;
    private final CreatorService creatorService;
    private final CreatorAccountRepository accountRepo;
    private final PgyRoleProbe roleProbe;
    private final XhsCookieService cookieService;
    private final JsonNode schema;

    public XhsFetchPgyKolTool(XhsSkillRunner runner,
                              CreatorService creatorService,
                              CreatorAccountRepository accountRepo,
                              PgyRoleProbe roleProbe,
                              XhsCookieService cookieService) {
        this.runner = runner;
        this.creatorService = creatorService;
        this.accountRepo = accountRepo;
        this.roleProbe = roleProbe;
        this.cookieService = cookieService;
        this.schema = ToolInputSchemas.object()
                .stringProp("keyword", "关键词（可选，如 '美妆'）", false)
                .integerProp("page", "页码，默认 1", false)
                .integerProp("pageSize", "每页数，默认 20，上限 50", false)
                .integerProp("followersMin", "粉丝下限，万为单位 → 传入原始数值（例如 10000）", false)
                .integerProp("followersMax", "粉丝上限", false)
                .enumProp("gender", "性别：0=全部 1=女 2=男", List.of("0", "1", "2"), false)
                .booleanProp("dryRun", "true 只返回不入库（默认）。false 会 upsert 成 CreatorAccount", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_fetch_pgy_kol"; }
    @Override public String description() {
        return "通过蒲公英后台拉小红书 KOL 列表，含粉丝/均赞/报价。用于选人。"
                + "需要后台录入 xhs_pgy cookie。dryRun=false 时会批量 upsert 到博主库。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return input != null && input.path("dryRun").asBoolean(true); }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("当前上下文缺少 orgTag");

        boolean dryRun = input.path("dryRun").asBoolean(true);
        int pageSize = Math.max(1, Math.min(input.path("pageSize").asInt(20), 50));
        int page = Math.max(1, input.path("page").asInt(1));

        // 预检：蒲公英 PuGongYingAPI 只对品牌主/机构账号有效。
        // 提前探测一次 user/info，避免用 KOL 号去跑 Python skill 导致 cookie 被连坑。
        var picked = cookieService.pickAvailable(orgTag, "xhs_pgy");
        if (picked.isEmpty()) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorType", "no_cookie");
            return ToolResult.error(ToolErrors.NO_TARGET,
                    "当前 org 下没有 ACTIVE 的 xhs_pgy cookie。请到'数据源 → 小蜜蜂 XHS Cookie'扫码/录入一个"
                            + "已开通'蒲公英品牌主/机构'资质的账号。", extra);
        }
        PgyRoleProbe.Result probe = roleProbe.probe(picked.get().cookie());
        if (!probe.reachable()) {
            cookieService.reportFailure(picked.get().cookieId(),
                    "pgy preflight unreachable: " + probe.reason());
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorType", "pgy_unreachable");
            extra.put("role", probe.role());
            extra.put("httpStatus", probe.httpStatus());
            extra.put("apiMsg", probe.apiMsg());
            return ToolResult.error("pgy_unreachable",
                    "蒲公英预检失败：" + probe.reason()
                            + "（cookie 可能已过期或被风控，建议重新扫码登录品牌主账号）。",
                    extra);
        }
        if (!probe.brandQualified()) {
            cookieService.reportSuccess(picked.get().cookieId()); // cookie 本身是活的，不要冤杀
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorType", "not_brand_account");
            extra.put("role", probe.role());
            extra.put("nickName", probe.nickName());
            extra.put("userId", probe.userId());
            return ToolResult.error("not_brand_account",
                    "当前 xhs_pgy cookie 登录的账号 [" + probe.nickName() + "] 角色是 "
                            + probe.role() + "（非品牌主/机构）。Spider_XHS 的蒲公英接口"
                            + "只对已在蒲公英后台开通'品牌主/机构'资质的账号有效。"
                            + "请换一个已开通品牌主资质的账号扫码/录入；KOL 号永远拉不到。",
                    extra);
        }
        cookieService.reportSuccess(picked.get().cookieId());

        XhsSkillRunner.RunRequest req = new XhsSkillRunner.RunRequest();
        req.orgTag = orgTag;
        req.sessionId = ctx.sessionId();
        req.skillName = "xhs-pgy-kol";
        req.scriptRelative = "scripts/fetch_pgy_kol.py";
        req.cookiePlatform = "xhs_pgy";
        req.extraArgs.add("--page");
        req.extraArgs.add(String.valueOf(page));
        req.extraArgs.add("--page-size");
        req.extraArgs.add(String.valueOf(pageSize));
        addOptional(req, input, "keyword", "--keyword");
        addOptional(req, input, "followersMin", "--followers-min");
        addOptional(req, input, "followersMax", "--followers-max");
        addOptional(req, input, "gender", "--gender");
        req.timeoutSeconds = 180;
        req.cancelled = ctx.cancelled();

        XhsSkillRunner.RunResult res = runner.run(req);
        if (!res.ok()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("errorType", res.errorType());
            return ToolResult.error("xhs-pgy-kol 执行失败: " + res.errorMessage(), detail);
        }

        JsonNode payload = res.payload();
        JsonNode kolsNode = payload.path("kols");
        List<Map<String, Object>> kols;
        try {
            kols = MAPPER.convertValue(kolsNode, new TypeReference<>() {});
        } catch (Exception e) {
            return ToolResult.error("kols 解析失败: " + e.getMessage());
        }
        if (kols == null) kols = List.of();
        int total = payload.path("total").asInt(kols.size());

        if (dryRun) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", total);
            out.put("kols", kols);
            out.put("dryRun", true);
            return ToolResult.of(out, String.format("拉到 %d 个 KOL（dryRun，未入库）", kols.size()));
        }

        int inserted = 0, updated = 0, skipped = 0;
        for (Map<String, Object> k : kols) {
            String uid = str(k, "platformUserId");
            if (uid == null || uid.isBlank()) { skipped++; continue; }
            boolean exists = accountRepo.findByPlatformAndPlatformUserId("xhs", uid).isPresent();
            try {
                CreatorService.CreatorAccountUpsertRequest up = new CreatorService.CreatorAccountUpsertRequest(
                        null,
                        orgTag,
                        "xhs",
                        uid,
                        str(k, "handle"),
                        str(k, "displayName"),
                        str(k, "avatarUrl"),
                        str(k, "bio"),
                        asLong(k.get("followers")),
                        asLong(k.get("following")),
                        asLong(k.get("likes")),
                        asLong(k.get("posts")),
                        asLong(k.get("avgLikes")),
                        asLong(k.get("avgComments")),
                        asDouble(k.get("hitRatio")),
                        asDouble(k.get("engagementRate")),
                        asBool(k.get("verified")),
                        str(k, "verifyType"),
                        str(k, "region"),
                        str(k, "homepageUrl"),
                        str(k, "categoryMain"),
                        str(k, "categorySub"),
                        toJson(k.get("platformTags")),
                        toJson(k.get("customFields"))
                );
                creatorService.upsertAccount(up);
                if (exists) updated++; else inserted++;
            } catch (Exception e) {
                log.warn("upsert KOL 失败 uid={} err={}", uid, e.getMessage());
                skipped++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("inserted", inserted);
        out.put("updated", updated);
        out.put("skipped", skipped);
        return ToolResult.of(out,
                String.format("拉到 %d 个 KOL → +%d 新增 / %d 更新 / %d 跳过",
                        kols.size(), inserted, updated, skipped));
    }

    private static void addOptional(XhsSkillRunner.RunRequest req, JsonNode input,
                                    String field, String flag) {
        if (input.hasNonNull(field)) {
            String v = input.get(field).asText("");
            if (!v.isBlank()) {
                req.extraArgs.add(flag);
                req.extraArgs.add(v);
            }
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }
    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return null; }
    }
    private static Boolean asBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).toLowerCase();
        return "true".equals(s) || "1".equals(s);
    }
    private static String toJson(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        try { return MAPPER.writeValueAsString(v); } catch (Exception e) { return null; }
    }
}
