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
import java.util.Map;

/**
 * {@code spotlight_target_keyword_match}：一批候选关键词跟聚光词库的匹配校验。
 *
 * <p>底层：{@code POST /api/open/jg/target/keyword/match}。
 * 把用户 / LLM 拼出来的关键词传进来，返回每个词是否在聚光官方词库里（in_thesaurus=true/false）。
 * 决定"这批词能不能直接进单元"。一次最多 150 个。只读。
 */
@Component
public class SpotlightTargetKeywordMatchTool implements Tool {

    private final SpotlightApiClient client;
    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotlightTargetKeywordMatchTool(SpotlightApiClient client) {
        this.client = client;
        this.schema = ToolInputSchemas.object()
                .arrayProp("keywords", "待校验的关键词数组，最多 150 个",
                        ToolInputSchemas.stringType(), true)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "spotlight_target_keyword_match"; }

    @Override public String description() {
        return "校验一批关键词是否在聚光官方词库里（in_thesaurus）。单次上限 150 个。"
                + "用于判断 LLM/运营挑出的词能否直接下发到单元。只读。";
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
        JsonNode kw = input.path("keywords");
        if (!kw.isArray() || kw.size() == 0) {
            return ToolResult.error("keywords 必须是非空字符串数组");
        }
        if (kw.size() > 150) {
            return ToolResult.error("keywords 数量超过 150 上限（当前 " + kw.size() + "），请分批调用");
        }
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("keywords");
        kw.forEach(e -> {
            if (e.isTextual()) arr.add(e.asText());
            else arr.add(e.toString());
        });

        SpotlightApiClient.Result r = client.post(ctx.orgTag(), "/jg/target/keyword/match", body);
        if (!r.ok()) return SpotlightBalanceInfoTool.mapError(r);

        JsonNode data = r.data();
        JsonNode matchInfos = data.path("match_infos");
        long matched = data.path("match_distinct_count").asLong(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalInput", kw.size());
        out.put("matchCount", matched);
        out.put("matchInfos", matchInfos);
        out.put("requestId", r.requestId());
        out.put("latencyMs", r.latencyMs());
        String summary = String.format("spotlight_target_keyword_match: %d/%d 在库 (%dms)",
                matched, kw.size(), r.latencyMs());
        return ToolResult.of(out, summary);
    }
}
