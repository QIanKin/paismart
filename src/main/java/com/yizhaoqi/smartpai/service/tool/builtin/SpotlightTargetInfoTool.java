package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.SpotlightApiClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code spotlight_target_info}：获取当前广告账户在指定营销目标下可用的全部定向项。
 *
 * <p>底层：{@code POST /api/open/jg/target/get_available_target_info}。
 * 返回 industry_interest / content_interest / shopping_interest / crowd_target / gender / age / area / device 的
 * <b>全量可选值树</b>。配合 {@link SpotlightCrowdEstimateTool} 可以闭环地做定向设计。只读。
 */
@Component
public class SpotlightTargetInfoTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightTargetInfoTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .integerProp("marketingTarget",
                        "营销目标：0=旧计划 1=应用下载 2=销售线索 3=商品推广 4=笔记推广 5=私信营销 8=直播推广 9=客资收集。"
                                + "一般笔记推广传 4，其他按 MAPI 文档。",
                        false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_target_info"; }

    @Override public String description() {
        return "获取当前广告账户在指定 marketingTarget 下可用的全部定向项（兴趣 / 人群包 / 性别 / 年龄 / 地域 / 设备）。"
                + "是做单元定向 / 人群预估（spotlight_crowd_estimate）前的必经一步。只读。";
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
        ObjectNode body = mapper.createObjectNode();
        if (input.hasNonNull("marketingTarget")) {
            body.put("marketing_target", input.get("marketingTarget").asInt());
        }
        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/target/get_available_target_info", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        JsonNode data = r.data();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("industryInterestTarget", data.path("industry_interest_target"));
        out.put("contentInterest", data.path("content_interest"));
        out.put("shoppingInterest", data.path("shopping_interest"));
        out.put("crowdTarget", data.path("crowd_target"));
        out.put("genderTargets", data.path("gender_targets"));
        out.put("ageTargets", data.path("age_target"));
        out.put("areaTargets", data.path("area_target"));
        out.put("deviceTargets", data.path("device_target"));
        out.put("children", data.path("children"));
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format("spotlight_target_info: ok (%dms)", r.latencyMs());
        return ToolResult.of(out, summary);
    }
}
