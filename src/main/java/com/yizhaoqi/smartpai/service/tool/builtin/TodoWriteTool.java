package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 自管 TODO 列表。对标 claude-code 的 TodoWrite 工具：让 Agent 把复杂任务拆解成
 * 可跟踪的 step，并在运行过程中更新状态。前端通过 TODO 事件展示。
 *
 * 存储：Redis 短存（24h TTL），scope = session。不落 MySQL，属于"过程副产物"。
 */
@Component
public class TodoWriteTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final JsonNode schema;

    public TodoWriteTool(StringRedisTemplate redis) {
        this.redis = redis;
        ObjectNode itemSchema = MAPPER.createObjectNode();
        itemSchema.put("type", "object");
        ObjectNode itemProps = MAPPER.createObjectNode();
        itemProps.set("id", ToolInputSchemas.stringType().put("description", "唯一 id"));
        itemProps.set("content", ToolInputSchemas.stringType().put("description", "任务内容，命令式短句"));
        ObjectNode statusProp = MAPPER.createObjectNode();
        statusProp.put("type", "string");
        statusProp.put("description", "状态");
        ArrayNode statusEnum = statusProp.putArray("enum");
        statusEnum.add("pending"); statusEnum.add("in_progress"); statusEnum.add("completed"); statusEnum.add("cancelled");
        itemProps.set("status", statusProp);
        itemSchema.set("properties", itemProps);
        ArrayNode req = itemSchema.putArray("required");
        req.add("id"); req.add("content"); req.add("status");

        this.schema = ToolInputSchemas.object()
                .arrayProp("todos", "完整的 TODO 列表（整体替换，不是增量）", itemSchema, true)
                .booleanProp("merge", "是否与已有列表按 id 合并；默认 false 表示整体替换", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "todo_write"; }

    @Override public String description() {
        return "创建/更新本轮 Agent 的 TODO 列表。适合在复杂任务（3 步以上）开始时和每一步完成后调用；"
                + "前端会把列表实时渲染给用户。同一时间只允许一个任务处于 in_progress。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isDestructive(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override public String userFacingName(JsonNode input) { return "更新 TODO"; }

    @Override public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        JsonNode todos = input.path("todos");
        if (!todos.isArray()) return ToolResult.error("todos 必须是数组");
        boolean merge = input.path("merge").asBoolean(false);

        String key = buildKey(ctx);
        ArrayNode merged;
        if (merge) {
            merged = readExisting(key);
            mergeById(merged, todos);
        } else {
            merged = MAPPER.createArrayNode();
            for (JsonNode t : todos) merged.add(t);
        }

        // 约束：至多一个 in_progress
        int inProgress = 0;
        for (JsonNode t : merged) {
            if ("in_progress".equals(t.path("status").asText())) inProgress++;
        }
        if (inProgress > 1) {
            return ToolResult.error("不允许多个 TODO 同时处于 in_progress");
        }

        redis.opsForValue().set(key, MAPPER.writeValueAsString(merged), TTL);

        // 走专用 todo WS 事件通道（不是 tool_progress），前端会活体渲染清单
        ctx.updateTodos(merged);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", merged.size());
        data.put("todos", merged);
        return ToolResult.of(data, "TODO 已更新：共 " + merged.size() + " 项");
    }

    private String buildKey(ToolContext ctx) {
        String scope = ctx.sessionId() != null ? ctx.sessionId() : "user:" + ctx.userId();
        return "agent:todo:" + scope;
    }

    private ArrayNode readExisting(String key) {
        String raw = redis.opsForValue().get(key);
        if (raw == null || raw.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node.isArray() ? (ArrayNode) node : MAPPER.createArrayNode();
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }

    private void mergeById(ArrayNode base, JsonNode patch) {
        Map<String, JsonNode> idx = new LinkedHashMap<>();
        for (JsonNode node : base) idx.put(node.path("id").asText(), node);
        for (JsonNode p : patch) idx.put(p.path("id").asText(), p);
        base.removeAll();
        for (JsonNode node : idx.values()) base.add(node);
    }
}
