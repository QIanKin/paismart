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
 * {@code spotlight_crowd_estimate}：人群预估。
 *
 * <p>底层：{@code POST /api/open/jg/crowd/estimate}。
 * 传入一套定向（targetConfig）+ 营销目标 / 广告类型 / 推广目标，返回这组条件能覆盖多大人群
 * （偏窄 / 合适 / 偏广）。是做定向设计时验证"是否够精准又不太窄"的关键工具。只读。
 */
@Component
public class SpotlightCrowdEstimateTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightCrowdEstimateTool(SpotlightApiClient client) {
        this.client = client;
        ObjectNode targetCfgSchema = mapper.createObjectNode();
        targetCfgSchema.put("type", "object");
        targetCfgSchema.put("description",
                "定向配置（unit.TargetConfig 原样透传）。可包含 industry_interest / content_interest / "
                        + "shopping_interest / crowd_target / gender_targets / age_targets / area_targets / device_targets。"
                        + "字段来自 spotlight_target_info 的返回。");
        this.schema = ToolInputSchemas.object()
                .objectProp("targetConfig",
                        "定向配置对象，字段参考 spotlight_target_info 的返回结构", targetCfgSchema, false)
                .integerProp("marketingTarget",
                        "营销目标：3=商品销量 4=笔记种草 8=直播推广 9=客资收集 10=抢占赛道 13=种草直达 14=直播预热", false)
                .integerProp("placement",
                        "广告类型：1=信息流 2=搜索推广 4=全站智投 7=视频内流", false)
                .integerProp("optimizeTarget",
                        "推广目标（见 MAPI 文档）：0=点击量 1=互动量 3=表单提交 4=商品成单 5=私信咨询 6=直播间观看 …", false)
                .integerProp("targetType", "定向类型：1=通投 / 2=智能定向 / 3=高级定向", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_crowd_estimate"; }

    @Override public String description() {
        return "聚光人群预估——给定一组定向 + 营销目标 + 广告类型，返回能覆盖的人群规模（精确到百万）和范围等级（偏窄/合适/偏广）。"
                + "做单元定向前评估圈选是否合理的唯一接口。只读。";
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
        if (input.hasNonNull("targetConfig") && input.get("targetConfig").isObject()) {
            body.set("target_config", input.get("targetConfig"));
        }
        putIfInt(body, "marketing_target", input, "marketingTarget");
        putIfInt(body, "placement", input, "placement");
        putIfInt(body, "optimize_target", input, "optimizeTarget");
        putIfInt(body, "target_type", input, "targetType");

        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/crowd/estimate", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        JsonNode data = r.data();
        String scope = switch (data.path("crowd_scope").asInt(0)) {
            case 1 -> "偏窄";
            case 2 -> "合适";
            case 3 -> "偏广";
            default -> "未知";
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("crowdNum", data.path("crowd_num").asText(""));
        out.put("rawCrowdNum", data.path("raw_crowd_num").asLong(0));
        out.put("crowdScope", scope);
        out.put("crowdScopeCode", data.path("crowd_scope").asInt(0));
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format("spotlight_crowd_estimate: %s 人（%s, %dms）",
                data.path("crowd_num").asText("?"), scope, r.latencyMs());
        return ToolResult.of(out, summary);
    }

    private static void putIfInt(ObjectNode body, String k, JsonNode input, String f) {
        if (input.hasNonNull(f)) body.put(k, input.get(f).asInt());
    }
}
