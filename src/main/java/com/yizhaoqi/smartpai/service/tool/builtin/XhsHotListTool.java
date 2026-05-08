package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.TikhubXhsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * xhs_hot_list：拉小红书热榜（trending topics）。走 TikHub 公开 API。
 *
 * <p>用于赛道选题、抢热点、监控品类话题热度。
 */
@Component
public class XhsHotListTool implements Tool {

    private final TikhubXhsService tikhubService;
    private final JsonNode schema;

    public XhsHotListTool(TikhubXhsService tikhubService) {
        this.tikhubService = tikhubService;
        this.schema = ToolInputSchemas.object()
                .integerProp("limit", "返回条数上限，默认 30", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_hot_list"; }

    @Override public String description() {
        return "拉小红书首页热榜（话题/上榜笔记/热度值）。可用于选题/抢热点。走 TikHub 公开 API。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        if (!tikhubService.configured()) {
            return ToolResult.error("provider_not_configured",
                    "TikHub 未配置：请设置 XHS_TIKHUB_ENABLED=true 与 XHS_TIKHUB_API_KEY");
        }
        int limit = Math.max(1, Math.min(input.path("limit").asInt(30), 200));

        TikhubXhsService.HotListResult res = tikhubService.fetchHotList();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (res.entries != null) {
            for (TikhubXhsService.HotListEntry e : res.entries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", e.title);
                m.put("heat", e.heat);
                m.put("icon", e.icon);
                m.put("link", e.link);
                rows.add(m);
                if (rows.size() >= limit) break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("items", rows);
        return ToolResult.of(out, String.format("热榜 → 拿到 %d 条", rows.size()));
    }
}
