package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.SpotlightApiClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code spotlight_name_dup_check}：计划 / 单元名称重复性校验。
 *
 * <p>底层：{@code POST /api/open/jg/data/check/name/dup}。
 * 创建 campaign 或 unit 之前先批量查一下名字是否已被占用，避免跑到创建阶段才报 409。
 * 单次 100 个名字。只读。
 */
@Component
public class SpotlightNameDupCheckTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightNameDupCheckTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .enumProp("type", "查询类型：campaign=计划 / unit=单元",
                        List.of("campaign", "unit"), true)
                .arrayProp("names", "名称数组，单次上限 100",
                        ToolInputSchemas.stringType(), true)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_name_dup_check"; }

    @Override public String description() {
        return "在创建 campaign/unit 前批量校验一堆名称是否重名。单次 100 条。只读。"
                + "返回每个名字的 true/false（true=已被占用）。";
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
        String type = input.path("type").asText("").trim();
        int typeCode = switch (type) {
            case "campaign" -> 1;
            case "unit" -> 2;
            default -> 0;
        };
        if (typeCode == 0) return ToolResult.error("type 必须是 'campaign' 或 'unit'");

        JsonNode names = input.path("names");
        if (!names.isArray() || names.size() == 0) {
            return ToolResult.error("names 必须是非空数组");
        }
        if (names.size() > 100) {
            return ToolResult.error("names 数量超过 100 上限（当前 " + names.size() + "）");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("type", typeCode);
        ArrayNode arr = body.putArray("name");
        names.forEach(e -> {
            if (e.isTextual()) arr.add(e.asText());
            else arr.add(e.toString());
        });

        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/data/check/name/dup", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        JsonNode data = r.data();
        JsonNode checkResult = data.path("check_result");
        int dupCount = 0;
        if (checkResult.isObject()) {
            var it = checkResult.fields();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getValue().asBoolean(false)) dupCount++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("total", names.size());
        out.put("duplicates", dupCount);
        out.put("checkResult", checkResult);
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format("spotlight_name_dup_check[%s]: %d/%d 重名 (%dms)",
                type, dupCount, names.size(), r.latencyMs());
        return ToolResult.of(out, summary);
    }
}
