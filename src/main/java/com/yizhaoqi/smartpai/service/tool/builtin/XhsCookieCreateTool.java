package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolErrors;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code xhs_cookie_create}：向当前 org 的 cookie 池新增一条数据源凭证。
 *
 * <p>内部包装 {@link XhsCookieService#create}：
 * <ul>
 *   <li>对 xhs_pc / xhs_creator / xhs_pgy / xhs_qianfan 四个 web 平台强制校验 a1/web_session/webId，缺字段直接抛
 *       {@link IllegalArgumentException}，被本工具翻译成 {@code cookie_invalid} 错误回灌给 LLM，让 LLM 知道该追问用户补字段。</li>
 *   <li>对 xhs_spotlight / xhs_competitor 期望收到 JSON 字符串（{@code accessToken / refreshToken / expiresAt}
 *       或 {@code supabaseUrl / apikey}），service 不会做字段级校验，只要非空就存。</li>
 * </ul>
 *
 * <p>权限：所有登录用户可用。破坏性：是（destructive=true），首次调用会要求二次确认。
 */
@Component
public class XhsCookieCreateTool implements Tool {

    private final XhsCookieService cookies;
    private final JsonNode schema;

    public XhsCookieCreateTool(XhsCookieService cookies) {
        this.cookies = cookies;
        this.schema = ToolInputSchemas.object()
                .enumProp("platform",
                        "数据源平台。xhs_pc/xhs_creator/xhs_pgy/xhs_qianfan 需 web cookie 字符串；"
                                + "xhs_spotlight 需 OAuth JSON；xhs_competitor 需 {supabaseUrl, apikey} JSON。",
                        List.of("xhs_pc", "xhs_creator", "xhs_pgy", "xhs_qianfan",
                                "xhs_spotlight", "xhs_competitor"),
                        true)
                .stringProp("cookie",
                        "凭证原文。web 平台是 'k=v; k=v;' 字符串；spotlight/competitor 是 JSON。明文传入，会自动 AES/GCM 加密落库。",
                        true)
                .stringProp("accountLabel",
                        "账号标签，用于多账号区分。例：'ZFC东方美妆种草集-HK1'。建议填，方便后续 xhs_cookie_list 肉眼识别。",
                        false)
                .stringProp("note", "备注（可选），128 字以内。", false)
                .integerProp("priority",
                        "优先级，大者优先，默认 10。公司共享池推荐 20；测试账号 5。",
                        false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_cookie_create"; }

    @Override public String description() {
        return "向当前企业的数据源凭证池新增一条记录（小红书 web cookie / 聚光 OAuth JSON / 竞品 Supabase JSON）。"
                + "对 web 平台会强制校验 a1/web_session/webId 缺失。所有登录用户可用，属于破坏性写操作，首次调用要求二次确认。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return false; }

    @Override public boolean isDestructive(JsonNode input) { return true; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        // Agent 一视同仁：不再按 role 限制。破坏性写操作由 requiresConfirmation 的二次确认兜底。
        // orgTag 缺失仍拒绝 —— 那是数据隔离保障，不是"权限"。
        if (ctx.orgTag() == null || ctx.orgTag().isBlank()) {
            return PermissionResult.deny("缺少 orgTag，无法定位当前组织");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String platform = text(input, "platform");
        String cookie = text(input, "cookie");
        String accountLabel = text(input, "accountLabel");
        String note = text(input, "note");
        Integer priority = input.hasNonNull("priority") ? input.get("priority").asInt() : null;

        if (platform == null || cookie == null) {
            return ToolResult.error(ToolErrors.BAD_REQUEST, "platform 和 cookie 是必填字段");
        }

        XhsCookie saved;
        try {
            saved = cookies.create(ctx.orgTag(), platform, cookie, accountLabel, note, priority,
                    "agent:" + (ctx.userId() == null ? "unknown" : ctx.userId()));
        } catch (IllegalArgumentException e) {
            // service 层对 web cookie 缺字段会抛 IllegalArgumentException，翻译成业务语义错误给 LLM
            return ToolResult.error(ToolErrors.COOKIE_INVALID,
                    "cookie 内容不合法：" + e.getMessage() + "。建议在数据源页重新扫码登录。");
        } catch (Exception e) {
            return ToolResult.error(ToolErrors.INTERNAL,
                    "创建 cookie 时出现未预期错误：" + e.getClass().getSimpleName() + "：" + e.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", saved.getId());
        data.put("orgTag", saved.getOwnerOrgTag());
        data.put("platform", saved.getPlatform());
        data.put("accountLabel", saved.getAccountLabel());
        data.put("cookiePreview", saved.getCookiePreview());
        data.put("cookieKeys", saved.getCookieKeys());
        data.put("status", saved.getStatus() == null ? null : saved.getStatus().name());
        data.put("priority", saved.getPriority());
        data.put("source", saved.getSource() == null ? null : saved.getSource().name());
        String summary = String.format("xhs_cookie_create → 新建 #%d platform=%s label=%s",
                saved.getId(), saved.getPlatform(),
                saved.getAccountLabel() == null ? "-" : saved.getAccountLabel());
        return ToolResult.of(data, summary);
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.hasNonNull(field)) return null;
        String s = n.get(field).asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }
}
