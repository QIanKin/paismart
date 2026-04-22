package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code xhs_cookie_delete}：硬删除一条数据源凭证。
 *
 * <p>为了配合未来统一的二次确认 hook，本工具显式声明 {@code isDestructive=true}；
 * 安全约束：必须传入 {@code confirm=true}，否则直接返回 {@code confirm_required} 错误，
 * 防止 LLM 误调用或被 prompt 注入。
 *
 * <p>删除前会再次 findById 做 org 归属校验；不属于当前 org 的记录视为 not_found。
 * 删除后的操作不可逆（DB 物理 DELETE），同 cookie 正在被采集脚本加载的风险由上层调度兜底——
 * 脚本会在下次调用 pickAvailable 时重新选出一条 ACTIVE 记录。
 */
@Component
public class XhsCookieDeleteTool implements Tool {

    private final XhsCookieService cookies;
    private final JsonNode schema;

    public XhsCookieDeleteTool(XhsCookieService cookies) {
        this.cookies = cookies;
        this.schema = ToolInputSchemas.object()
                .integerProp("id", "要删除的 cookie 行 id。必填。", true)
                .booleanProp("confirm",
                        "必须显式传 true 才真正删除。agent 应先用 xhs_cookie_list 或 xhs_cookie_ping 查明确认，"
                                + "再在下一轮 tool call 带 confirm=true 真删。",
                        true)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_cookie_delete"; }

    @Override public String description() {
        return "硬删除一条数据源凭证（物理 DELETE，不可恢复）。必须带 confirm=true。仅管理员可用。"
                + "建议先 xhs_cookie_list 查看后再调用；日常轮换优先用 xhs_cookie_update 改 cookie 字段。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return false; }

    @Override public boolean isDestructive(JsonNode input) { return true; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        if (!"admin".equalsIgnoreCase(ctx.role())) {
            return PermissionResult.deny("xhs_cookie_delete 仅管理员可用，当前 role=" + ctx.role());
        }
        if (ctx.orgTag() == null || ctx.orgTag().isBlank()) {
            return PermissionResult.deny("缺少 orgTag，拒绝");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        if (!input.hasNonNull("id")) return ToolResult.error("id 必填");
        boolean confirm = input.hasNonNull("confirm") && input.get("confirm").asBoolean(false);
        if (!confirm) {
            return ToolResult.error("confirm_required: 必须传 confirm=true 才会真正删除。"
                    + "建议先调用 xhs_cookie_list 让用户确认后再带 confirm=true 重试。");
        }
        long id = input.get("id").asLong();

        Optional<XhsCookie> before = cookies.findById(id, ctx.orgTag());
        if (before.isEmpty()) {
            return ToolResult.error("not_found: cookie #" + id + " 不存在或不属于当前 org");
        }
        XhsCookie snapshot = before.get();

        boolean ok;
        try {
            ok = cookies.delete(id, ctx.orgTag());
        } catch (Exception e) {
            return ToolResult.error("internal: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (!ok) {
            return ToolResult.error("not_found: delete 返回 false（并发删除？）");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", true);
        data.put("id", id);
        data.put("platform", snapshot.getPlatform());
        data.put("accountLabel", snapshot.getAccountLabel());
        String summary = String.format("xhs_cookie_delete → #%d platform=%s label=%s 已物理删除",
                id, snapshot.getPlatform(),
                snapshot.getAccountLabel() == null ? "-" : snapshot.getAccountLabel());
        return ToolResult.of(data, summary);
    }
}
