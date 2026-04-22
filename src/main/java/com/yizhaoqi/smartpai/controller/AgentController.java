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
import java.util.List;
import java.util.Map;

/**
 * Agent 相关的 REST 入口：
 * <ul>
 *   <li>{@code GET /api/v1/agent/tools}          - 扁平工具清单（调试用）</li>
 *   <li>{@code GET /api/v1/agent/tools/schema}   - OpenAI 格式 tools manifest（调试用）</li>
 *   <li>{@code GET /api/v1/agent/tools/catalog}  - Phase 4c：按业务域分组 + 人话标签，供前端聊天抽屉渲染</li>
 *   <li>{@code GET /api/v1/agent/todos/{scope}}  - 读取某个 scope（session / user）的最新 TODO 列表</li>
 * </ul>
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

    /**
     * Phase 4c：面向普通用户的"工具目录"。
     * 按 9 个业务域分组，每个工具给出 userFacingName / 是否只读 / 是否破坏性 / 一句话说明。
     * 前端聊天底部的"工具 (N)"按钮点开后，直接用这个接口的 data 渲染抽屉。
     */
    @GetMapping("/tools/catalog")
    public ResponseEntity<?> toolsCatalog() {
        // 有序的域：数据源和常用抓取放前面；系统/文件系统这些排后面
        List<DomainSpec> domains = List.of(
                new DomainSpec("data_source", "数据源与凭证", "管理小红书 / 聚光账号与 cookie",
                        List.of("xhs_cookie_", "spotlight_oauth_", "xhs_qr_login_")),
                new DomainSpec("xhs_fetch", "小红书内容抓取", "走 web cookie 或蒲公英拿博主/笔记/报价",
                        List.of("xhs_search_notes", "xhs_fetch_pgy_kol", "xhs_pgy_kol_detail",
                                "xhs_refresh_creator", "xhs_download_video", "xhs_outreach_comment")),
                new DomainSpec("spotlight_data", "聚光广告数据（自家账户）",
                        "查自家广告账户余额 / 计划 / 单元 / 投放报表；不是博主数据",
                        List.of("spotlight_balance_info", "spotlight_campaign_list",
                                "spotlight_unit_list", "spotlight_report_offline_advertiser")),
                new DomainSpec("qianggua", "千瓜数据", "千瓜平台抓取",
                        List.of("qiangua", "qianggua")),
                new DomainSpec("creator", "创作者库", "博主库增删改查、批量录入",
                        List.of("creator_", "creator.get_posts")),
                new DomainSpec("project_roster", "项目名册", "项目下已选博主的批量管理",
                        List.of("project_roster_")),
                new DomainSpec("schedule", "任务调度", "定时任务 CRUD",
                        List.of("schedule_")),
                new DomainSpec("knowledge", "知识库与搜索", "内部 RAG / 外部联网",
                        List.of("knowledge_search", "web_search", "web_fetch")),
                new DomainSpec("agent_control", "对话与流程", "反问用户、TODO、技能、休眠",
                        List.of("ask_user_question", "todo_write", "use_skill", "sleep", "tool_search")),
                new DomainSpec("system", "系统与运维", "只读系统状态、管理员工具",
                        List.of("system_status")),
                new DomainSpec("filesystem", "文件系统（危险）", "读写服务器本地文件",
                        List.of("file_read", "file_write", "file_edit", "glob", "grep", "bash"))
        );

        Map<String, ObjectNode> groupNodes = new LinkedHashMap<>();
        Map<String, Integer> groupCounts = new LinkedHashMap<>();
        for (DomainSpec d : domains) {
            ObjectNode g = mapper.createObjectNode();
            g.put("id", d.id);
            g.put("name", d.name);
            g.put("description", d.description);
            g.putArray("tools");
            groupNodes.put(d.id, g);
            groupCounts.put(d.id, 0);
        }
        ObjectNode otherGroup = mapper.createObjectNode();
        otherGroup.put("id", "other");
        otherGroup.put("name", "其他");
        otherGroup.put("description", "未归类工具");
        otherGroup.putArray("tools");

        int total = 0;
        for (Tool t : toolRegistry.all()) {
            total++;
            String matched = null;
            for (DomainSpec d : domains) {
                for (String prefix : d.prefixes) {
                    if (t.name().startsWith(prefix) || t.name().equals(prefix)) {
                        matched = d.id;
                        break;
                    }
                }
                if (matched != null) break;
            }
            ObjectNode entry = mapper.createObjectNode();
            entry.put("name", t.name());
            entry.put("userFacingName", t.userFacingName(null));
            entry.put("description", t.description());
            entry.put("readOnly", t.isReadOnly(null));
            entry.put("destructive", t.isDestructive(null));
            ObjectNode target = matched == null ? otherGroup : groupNodes.get(matched);
            ((ArrayNode) target.get("tools")).add(entry);
            if (matched != null) groupCounts.merge(matched, 1, Integer::sum);
        }

        ArrayNode groups = mapper.createArrayNode();
        for (ObjectNode g : groupNodes.values()) {
            g.put("count", ((ArrayNode) g.get("tools")).size());
            if (((ArrayNode) g.get("tools")).size() > 0) groups.add(g);
        }
        if (((ArrayNode) otherGroup.get("tools")).size() > 0) {
            otherGroup.put("count", ((ArrayNode) otherGroup.get("tools")).size());
            groups.add(otherGroup);
        }

        ObjectNode data = mapper.createObjectNode();
        data.put("total", total);
        data.set("groups", groups);
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", data));
    }

    /** 把一个业务域的元数据拍成 record，给 toolsCatalog 用。 */
    private record DomainSpec(String id, String name, String description, List<String> prefixes) {}

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
