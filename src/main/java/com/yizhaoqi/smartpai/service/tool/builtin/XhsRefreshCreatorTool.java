package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsRefreshService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * xhs_refresh_creator：Agent 调用版本的"一键刷新"，业务逻辑全部走 {@link XhsRefreshService}，
 * 与后台管理页的刷新按钮共享同一链路。
 */
@Component
public class XhsRefreshCreatorTool implements Tool {

    private final XhsRefreshService refreshService;
    private final JsonNode schema;

    public XhsRefreshCreatorTool(XhsRefreshService refreshService) {
        this.refreshService = refreshService;
        this.schema = ToolInputSchemas.object()
                .integerProp("accountId", "博主库里的账号 id（仅 platform=xhs 有效）", true)
                .integerProp("limit", "最近几条笔记（默认 20，上限 200）", false)
                .booleanProp("dryRun", "true 只拉取不入库，便于预览", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_refresh_creator"; }

    @Override public String description() {
        return "用小红书 PC 端 cookie 拉取指定博主最近 N 条笔记并入库。输入为 creator_accounts.id（platform=xhs）。"
                + "需要后台 admin 预先录入 xhs_pc cookie，失败时会返回 no_cookie 或 cookie_invalid。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return input != null && input.path("dryRun").asBoolean(false); }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        long accountId = input.path("accountId").asLong(-1);
        int limit = input.path("limit").asInt(20);
        boolean dryRun = input.path("dryRun").asBoolean(false);

        XhsRefreshService.Result r = refreshService.refreshAccount(
                accountId, ctx.orgTag(), limit, dryRun, ctx.sessionId(), ctx.cancelled());
        if (!r.ok()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("errorType", r.errorType());
            detail.put("accountId", accountId);
            return ToolResult.error(
                    "xhs-user-notes 执行失败: " + r.errorMessage(), detail);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", r.accountId());
        out.put("fetched", r.fetched());
        if (r.dryRun()) {
            out.put("preview", r.preview());
            out.put("dryRun", true);
            return ToolResult.of(out, "dryRun：拉到 " + r.fetched() + " 条笔记，未入库");
        }
        out.put("inserted", r.inserted());
        out.put("updated", r.updated());
        out.put("skipped", r.skipped());
        return ToolResult.of(out,
                String.format("刷新 account #%d：拉到 %d 条 → +%d 新增 / %d 更新 / %d 跳过",
                        r.accountId(), r.fetched(), r.inserted(), r.updated(), r.skipped()));
    }
}
