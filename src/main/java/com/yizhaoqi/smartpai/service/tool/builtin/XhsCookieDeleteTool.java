package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.service.tool.ConfirmationRequest;
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
 * {@code xhs_cookie_delete}：硬删除一条数据源凭证。
 *
 * <p>走 Phase 3b 统一的 {@link ConfirmationRequest} 协议 —— 不再在 schema 里暴露 {@code confirm} 字段，
 * 由 {@link com.yizhaoqi.smartpai.service.tool.ToolExecutor} 负责拦截首次调用并要求 LLM
 * 在二次调用时带上 {@code _confirm=true} + {@code _confirmToken=<原 token>}。
 *
 * <p>删除前会再次 findById 做 org 归属校验；不属于当前 org 的记录视为 not_found。
 * 删除不可逆（DB 物理 DELETE），但同 cookie 正在被采集脚本加载的风险由上层调度兜底——
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
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_cookie_delete"; }

    @Override public String description() {
        return "硬删除一条数据源凭证（物理 DELETE，不可恢复）。所有登录用户可用，属于破坏性操作，"
                + "首次调用会返回 confirmation_required，LLM 需要用 ask_user_question 取得用户同意后，"
                + "带上 _confirm=true 和 _confirmToken 再调一次。"
                + "日常轮换优先用 xhs_cookie_update 改 cookie 字段。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return false; }

    @Override public boolean isDestructive(JsonNode input) { return true; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        // Agent godmode：不再按 role 限制。删除操作依赖 requiresConfirmation 二次确认兜底。
        if (ctx.orgTag() == null || ctx.orgTag().isBlank()) {
            return PermissionResult.deny("缺少 orgTag，无法定位当前组织");
        }
        return PermissionResult.allow();
    }

    /**
     * 任何参数都走确认协议。summary 尽量带上"要删的是哪条"—— 查一次 DB 拿 platform+label
     * 比让用户只看到一个 id 好太多。查不到就让 ToolExecutor 继续走，真 call 时再报 not_found。
     */
    @Override
    public ConfirmationRequest requiresConfirmation(ToolContext ctx, JsonNode input) {
        if (input == null || !input.hasNonNull("id")) {
            return ConfirmationRequest.of("xhs_cookie_delete：参数缺少 id，仍需确认。",
                    "未指定目标 id，拒绝无差别删除。",
                    List.of("无法定位到具体 cookie 行"));
        }
        long id = input.get("id").asLong();
        Optional<XhsCookie> opt = cookies.findById(id, ctx.orgTag());
        String summary;
        List<String> risks;
        if (opt.isEmpty()) {
            summary = "xhs_cookie_delete：准备物理删除 cookie #" + id + "（当前 org 下未找到，仍需确认后走正式流程）。";
            risks = List.of("可能已被别人删除或不属于当前 org");
        } else {
            XhsCookie c = opt.get();
            summary = String.format("xhs_cookie_delete：将物理删除 cookie #%d (platform=%s, label=%s, status=%s)",
                    c.getId(), c.getPlatform(),
                    c.getAccountLabel() == null ? "-" : c.getAccountLabel(),
                    c.getStatus());
            risks = List.of(
                    "DB 物理 DELETE，不可回滚",
                    "若该行正在被采集脚本使用，下次轮转会挑其他 ACTIVE 记录",
                    "若是公司共享池唯一可用凭证，删除后该 org 暂时失去对应平台的采集能力");
        }
        return ConfirmationRequest.of(summary, "cookie 一旦删除无法恢复，请向用户复述上面 summary 后再放行。", risks);
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        if (!input.hasNonNull("id")) return ToolResult.error(ToolErrors.BAD_REQUEST, "id 是必填字段");
        long id = input.get("id").asLong();

        Optional<XhsCookie> before = cookies.findById(id, ctx.orgTag());
        if (before.isEmpty()) {
            return ToolResult.error(ToolErrors.NOT_FOUND,
                    "cookie #" + id + " 不存在或不属于当前 org");
        }
        XhsCookie snapshot = before.get();

        boolean ok;
        try {
            ok = cookies.delete(id, ctx.orgTag());
        } catch (Exception e) {
            return ToolResult.error(ToolErrors.INTERNAL,
                    "删除 cookie 时出现未预期错误：" + e.getClass().getSimpleName() + "：" + e.getMessage());
        }
        if (!ok) {
            return ToolResult.error(ToolErrors.NOT_FOUND,
                    "cookie #" + id + " 已被并发删除");
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
