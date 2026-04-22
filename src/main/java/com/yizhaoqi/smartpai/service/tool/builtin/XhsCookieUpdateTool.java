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
import java.util.Optional;

/**
 * {@code xhs_cookie_update}：修改已有 cookie 行的字段。
 *
 * <p>典型用法：
 * <ul>
 *   <li>轮换 cookie：传 {@code cookie=新串}，会重置 failCount / lastError，其他字段保持不变</li>
 *   <li>禁用账号：传 {@code status=DISABLED}</li>
 *   <li>恢复账号：传 {@code status=ACTIVE}</li>
 *   <li>调优先级：传 {@code priority=20}</li>
 * </ul>
 *
 * <p>对 web 平台（xhs_pc 等）传 cookie 时会强制校验 a1/web_session/webId。权限：仅管理员。破坏性：是。
 */
@Component
public class XhsCookieUpdateTool implements Tool {

    private final XhsCookieService cookies;
    private final JsonNode schema;

    public XhsCookieUpdateTool(XhsCookieService cookies) {
        this.cookies = cookies;
        this.schema = ToolInputSchemas.object()
                .integerProp("id", "要更新的 cookie 行 id。必填。", true)
                .stringProp("cookie",
                        "新的凭证原文（留空 = 不改）。web 平台是 'k=v;' 字符串；spotlight/competitor 是 JSON。",
                        false)
                .stringProp("accountLabel", "新账号标签（留空 = 不改）。", false)
                .stringProp("note", "新备注（留空 = 不改）。", false)
                .integerProp("priority", "新优先级（留空 = 不改）。", false)
                .enumProp("status",
                        "目标状态。ACTIVE=启用；DISABLED=临时禁用；EXPIRED=标记失效；BANNED=被平台封禁。留空 = 不改。",
                        List.of("ACTIVE", "DISABLED", "EXPIRED", "BANNED"),
                        false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_cookie_update"; }

    @Override public String description() {
        return "更新已有数据源凭证的字段（cookie 原文 / 标签 / 备注 / 优先级 / 状态）。"
                + "对 web 平台传 cookie 时会强制校验 a1/web_session/webId。"
                + "仅管理员可用，属于破坏性写操作。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return false; }

    @Override public boolean isDestructive(JsonNode input) { return true; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        if (!"admin".equalsIgnoreCase(ctx.role())) {
            return PermissionResult.deny("xhs_cookie_update 仅管理员可用，当前 role=" + ctx.role());
        }
        if (ctx.orgTag() == null || ctx.orgTag().isBlank()) {
            return PermissionResult.deny("缺少 orgTag，拒绝");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        if (!input.hasNonNull("id")) return ToolResult.error(ToolErrors.BAD_REQUEST, "id 是必填字段");
        long id = input.get("id").asLong();
        String cookie = text(input, "cookie");
        String accountLabel = text(input, "accountLabel");
        String note = text(input, "note");
        Integer priority = input.hasNonNull("priority") ? input.get("priority").asInt() : null;
        XhsCookie.Status status = null;
        String statusStr = text(input, "status");
        if (statusStr != null) {
            try {
                status = XhsCookie.Status.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ToolResult.error(ToolErrors.BAD_REQUEST,
                        "status 不是合法值：" + statusStr + "（可选 ACTIVE / EXPIRED / DISABLED）");
            }
        }

        Optional<XhsCookie> updated;
        try {
            updated = cookies.update(id, ctx.orgTag(), cookie, accountLabel, note, priority, status);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(ToolErrors.COOKIE_INVALID,
                    "cookie 内容不合法：" + e.getMessage() + "。建议在数据源页重新扫码。");
        } catch (Exception e) {
            return ToolResult.error(ToolErrors.INTERNAL,
                    "更新 cookie 时出现未预期错误：" + e.getClass().getSimpleName() + "：" + e.getMessage());
        }

        if (updated.isEmpty()) {
            return ToolResult.error(ToolErrors.NOT_FOUND,
                    "cookie #" + id + " 不存在或不属于当前 org");
        }
        XhsCookie c = updated.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", c.getId());
        data.put("platform", c.getPlatform());
        data.put("accountLabel", c.getAccountLabel());
        data.put("cookiePreview", c.getCookiePreview());
        data.put("cookieKeys", c.getCookieKeys());
        data.put("status", c.getStatus() == null ? null : c.getStatus().name());
        data.put("priority", c.getPriority());
        String summary = String.format("xhs_cookie_update → #%d 已更新 (status=%s priority=%s)",
                c.getId(), c.getStatus(), c.getPriority());
        return ToolResult.of(data, summary);
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.hasNonNull(field)) return null;
        String s = n.get(field).asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }
}
