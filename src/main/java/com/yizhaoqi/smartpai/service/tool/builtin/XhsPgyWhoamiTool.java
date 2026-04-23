package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolErrors;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.PgyRoleProbe;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code xhs_pgy_whoami}：用当前 xhs_pgy cookie 探一下账号角色——品牌主 / 机构 / KOL / 未登录。
 *
 * <p>Spider_XHS 的 PuGongYingAPI（给 {@code xhs_fetch_pgy_kol} / {@code xhs_pgy_kol_detail} 用的）
 * 只对"品牌主/机构"账号有效。如果用个人/KOL 账号扫码登入，cookie 本身拿得到，但所有
 * {@code /api/solar/cooperator/*} 接口都会挂，agent 会看起来"问两个蒲公英问题就全挂"。
 *
 * <p>本工具是只读的、不会污染 cookie 状态；LLM 应该在第一次需要 pgy 数据前先调一次确认。
 */
@Component
public class XhsPgyWhoamiTool implements Tool {

    private final XhsCookieService cookieService;
    private final PgyRoleProbe roleProbe;
    private final JsonNode schema;

    public XhsPgyWhoamiTool(XhsCookieService cookieService, PgyRoleProbe roleProbe) {
        this.cookieService = cookieService;
        this.roleProbe = roleProbe;
        this.schema = ToolInputSchemas.object()
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_pgy_whoami"; }

    @Override public String description() {
        return "探测当前 xhs_pgy cookie 背后账号的角色：品牌主 brand / 机构 agency / 博主 kol / 未登录 anonymous。"
                + "Spider_XHS 的蒲公英接口只对 brand/agency 账号有效，KOL 账号会全部挂。"
                + "调用 xhs_fetch_pgy_kol / xhs_pgy_kol_detail 之前先用这个确认账号资质；"
                + "如果不是 brand/agency，agent 应直接告诉用户换账号而不是反复重试。只读，不会伤 cookie。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        if (ctx.orgTag() == null || ctx.orgTag().isBlank()) {
            return PermissionResult.deny("缺少 orgTag，无法挑选 xhs_pgy cookie");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        Optional<XhsCookieService.Picked> picked = cookieService.pickAvailable(ctx.orgTag(), "xhs_pgy");
        if (picked.isEmpty()) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorType", "no_cookie");
            return ToolResult.error(ToolErrors.NO_TARGET,
                    "当前 org 下没有 ACTIVE 的 xhs_pgy cookie。请到'数据源 → 小蜜蜂 XHS Cookie'重新扫码/录入。",
                    extra);
        }

        PgyRoleProbe.Result result = roleProbe.probe(picked.get().cookie());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cookieId", picked.get().cookieId());
        out.put("role", result.role());
        out.put("brandQualified", result.brandQualified());
        out.put("reachable", result.reachable());
        out.put("userId", result.userId());
        out.put("nickName", result.nickName());
        out.put("httpStatus", result.httpStatus());
        out.put("apiCode", result.apiCode());
        out.put("apiMsg", result.apiMsg());
        out.put("latencyMs", result.latencyMs());

        String hint;
        if (!result.reachable()) {
            // HTTP/业务层面就挂了——cookie 大概率是真坏了，但这里不激进 markDead，
            // 只 reportFailure 让连续 5 次后自然过渡到 EXPIRED，避免一次探测就把 cookie 废掉。
            cookieService.reportFailure(picked.get().cookieId(),
                    "pgy whoami unreachable: " + result.reason());
            hint = "蒲公英 user/info 不可达：" + result.reason();
            if (result.bodyHead() != null) {
                out.put("bodyHead", result.bodyHead());
            }
            out.put("hint", hint);
            Map<String, Object> extra = new LinkedHashMap<>(out);
            return ToolResult.error("pgy_unreachable", hint, extra);
        }

        cookieService.reportSuccess(picked.get().cookieId());
        if (result.brandQualified()) {
            hint = String.format("账号 [%s] 角色=%s，可直接使用 xhs_fetch_pgy_kol / xhs_pgy_kol_detail 拉 KOL。",
                    result.nickName(), result.role());
        } else {
            hint = "账号 [" + result.nickName() + "] 登录 OK 但角色=" + result.role() + "（非 brand/agency）。"
                    + "Spider_XHS 的蒲公英接口只对品牌主/机构账号有效，该账号调 xhs_fetch_pgy_kol / "
                    + "xhs_pgy_kol_detail 会全部挂。请换一个已在蒲公英开通'品牌主/机构'资质的账号扫码或录入。";
        }
        out.put("hint", hint);
        return ToolResult.of(out, String.format(
                "xhs_pgy_whoami: role=%s qualified=%s (%dms)",
                result.role(), result.brandQualified(), result.latencyMs()));
    }
}
