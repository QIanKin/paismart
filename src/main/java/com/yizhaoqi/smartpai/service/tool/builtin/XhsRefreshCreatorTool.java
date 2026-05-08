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
 * xhs_refresh_creator：刷新博主库里某条 account 的资料和最近 N 条笔记。
 *
 * <p>当前实现统一走 TikHub 公开 API（{@link XhsRefreshService}）：
 * <ol>
 *   <li>有 platformUserId 或 homepage_url（含 /user/profile/&lt;userId&gt;）→ 直接拿 userId；</li>
 *   <li>否则用 handle/displayName 调 TikHub {@code search_users}（昵称搜索）→ 取最佳匹配的 userId；</li>
 *   <li>有了 userId 之后调 {@code fetch_user_info} + {@code fetch_user_notes} 拉资料和近期笔记，
 *       upsert 进 {@code creator_posts} 并回填 {@code creator_accounts} 的统计字段。</li>
 * </ol>
 *
 * <p>这条链路不依赖任何 cookie / 千瓜抓取，零封号风险。
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
        return "刷新小红书博主资料/最近 N 条笔记。统一走 TikHub 公开 API：有 platformUserId/主页链接直接用，"
                + "没有就用 handle/displayName 调 search_users 拿到 userId，再 fetch_user_info + fetch_user_notes。"
                + "输入是博主库 account id。零 cookie 依赖。";
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
                    "xhs_refresh_creator 执行失败: " + r.errorMessage(), detail);
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
