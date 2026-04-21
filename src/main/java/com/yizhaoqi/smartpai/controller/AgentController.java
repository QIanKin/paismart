package com.yizhaoqi.smartpai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 相关的 REST 入口：
 *  - GET /api/v1/agent/tools            返回已注册工具清单（供前端展示工具能力）
 *  - GET /api/v1/agent/tools/schema     返回 OpenAI 格式的 tools manifest（调试用）
 *  - GET /api/v1/agent/todos/{scope}    读取某个 scope（session / user）的最新 TODO 列表
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final ToolRegistry toolRegistry;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AgentController(ToolRegistry toolRegistry, StringRedisTemplate redis, ObjectMapper mapper) {
        this.toolRegistry = toolRegistry;
        this.redis = redis;
        this.mapper = mapper;
    }

    @GetMapping("/tools")
    public ResponseEntity<?> listTools() {
        ArrayNode arr = mapper.createArrayNode();
        for (Tool t : toolRegistry.all()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", t.name());
            node.put("description", t.description());
            node.put("userFacingName", t.userFacingName(null));
            node.put("readOnly", t.isReadOnly(null));
            node.put("destructive", t.isDestructive(null));
            node.put("concurrencySafe", t.isConcurrencySafe(null));
            node.set("inputSchema", t.inputSchema());
            arr.add(node);
        }
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", arr));
    }

    @GetMapping("/tools/schema")
    public ResponseEntity<?> toolsSchema() {
        ArrayNode manifest = toolRegistry.toOpenAiManifestAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("message", "ok");
        out.put("data", manifest);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/todos/{scope}")
    public ResponseEntity<?> getTodos(@PathVariable("scope") String scope) {
        String raw = redis.opsForValue().get("agent:todo:" + scope);
        if (raw == null) {
            return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", mapper.createArrayNode()));
        }
        try {
            JsonNode node = mapper.readTree(raw);
            return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", node));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("code", 500, "message", "invalid todo json: " + e.getMessage(), "data", null));
        }
    }
}
