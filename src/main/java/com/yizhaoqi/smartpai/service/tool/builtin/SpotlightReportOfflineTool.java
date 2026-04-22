package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolErrors;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.SpotlightApiClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code spotlight_report_offline_advertiser}：账户级离线报表（昨日及更早的全量消耗 / 曝光 / 点击 / ROI 等）。
 *
 * <p>底层：{@code POST /api/open/jg/data/report/offline/account}。
 * 必填 start_date / end_date（格式 yyyy-MM-dd，必须是"昨天"及以前，MAPI 不提供"今天"的离线数据）。
 * 区间不能超过 31 天；超过的话返回 business_error，不在本 tool 内额外截断，方便 LLM 获得准确 MAPI 反馈。
 */
@Component
public class SpotlightReportOfflineTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightReportOfflineTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .stringProp("startDate", "起始日期 yyyy-MM-dd，必须是昨天及更早", true)
                .stringProp("endDate", "结束日期 yyyy-MM-dd（含），不能晚于昨天", true)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_report_offline_advertiser"; }

    @Override public String description() {
        return "拉取当前聚光广告账户的【账户级离线报表】：给定日期区间（最多 31 天），返回全量消耗 / 曝光 / 点击 / CTR / CPM / ROI 等运营指标。"
                + "只读；用于复盘 / 周报。"
                + "注意：只覆盖昨天及更早的数据，今天实时数据请用 spotlight_report_realtime_advertiser（尚未实现则提示用户）。"
                + "这是自家广告投放数据，不是博主/笔记。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        if (ctx.orgTag() == null || ctx.orgTag().isBlank()) {
            return PermissionResult.deny("缺少 orgTag，无法定位聚光凭证");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String startDate = input.path("startDate").asText("");
        String endDate = input.path("endDate").asText("");
        if (startDate.isBlank() || endDate.isBlank()) {
            return ToolResult.error(ToolErrors.BAD_REQUEST,
                    "startDate / endDate 都必填，格式 yyyy-MM-dd");
        }
        try {
            LocalDate.parse(startDate);
            LocalDate.parse(endDate);
        } catch (DateTimeParseException e) {
            return ToolResult.error(ToolErrors.BAD_REQUEST,
                    "日期格式必须是 yyyy-MM-dd，当前：start=" + startDate + " end=" + endDate);
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("start_date", startDate);
        body.put("end_date", endDate);

        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/data/report/offline/account", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startDate", startDate);
        out.put("endDate", endDate);
        out.put("totalCount", r.data().path("total_count").asInt(0));
        out.put("aggregation", r.data().path("aggregation_data"));
        out.put("daily", r.data().path("data_list"));
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format(
                "spotlight_report_offline_advertiser: %s → %s totalCount=%d (req_id=%s, %dms)",
                startDate, endDate, out.get("totalCount"), r.requestId(), r.latencyMs());
        return ToolResult.of(out, summary);
    }
}
