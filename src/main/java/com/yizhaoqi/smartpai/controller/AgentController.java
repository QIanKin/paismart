package com.yizhaoqi.smartpai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.repository.agent.ChatSessionRepository;
import com.yizhaoqi.smartpai.service.agent.AgentUserResolver;
import com.yizhaoqi.smartpai.service.agent.FeatureFlagService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolRegistry;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
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
    private final JwtUtils jwtUtils;
    private final AgentUserResolver userResolver;
    private final ChatSessionRepository chatSessionRepository;
    private final FeatureFlagService featureFlags;

    public AgentController(ToolRegistry toolRegistry,
                           StringRedisTemplate redis,
                           ObjectMapper mapper,
                           JwtUtils jwtUtils,
                           AgentUserResolver userResolver,
                           ChatSessionRepository chatSessionRepository,
                           FeatureFlagService featureFlags) {
        this.toolRegistry = toolRegistry;
        this.redis = redis;
        this.mapper = mapper;
        this.jwtUtils = jwtUtils;
        this.userResolver = userResolver;
        this.chatSessionRepository = chatSessionRepository;
        this.featureFlags = featureFlags;
    }

    @GetMapping("/tools")
    public ResponseEntity<?> listTools() {
        ArrayNode arr = mapper.createArrayNode();
        JsonNode empty = mapper.createObjectNode();
        for (Tool t : toolRegistry.all()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", t.name());
            node.put("description", t.description());
            node.put("userFacingName", safeUserFacingName(t, empty));
            node.put("readOnly", safeReadOnly(t, empty));
            node.put("destructive", safeDestructive(t, empty));
            node.put("concurrencySafe", safeConcurrencySafe(t, empty));
            node.set("inputSchema", t.inputSchema());
            arr.add(node);
        }
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", arr));
    }

    // 目录/清单端点只是元数据展示，没有真实 input，
    // 有些工具内部会看 input 做动态判定（例如 bash cmd 判危险）；
    // 这里要兜底：传空 JSON，工具方法里只要是 path()/hasNonNull() 就安全；
    // 万一某个工具仍然抛异常，按"保守默认"返回（非只读、非破坏、非并发安全）。
    private static String safeUserFacingName(Tool t, JsonNode empty) {
        try { return t.userFacingName(empty); } catch (Exception e) { return t.name(); }
    }
    private static boolean safeReadOnly(Tool t, JsonNode empty) {
        try { return t.isReadOnly(empty); } catch (Exception e) { return false; }
    }
    private static boolean safeDestructive(Tool t, JsonNode empty) {
        try { return t.isDestructive(empty); } catch (Exception e) { return false; }
    }
    private static boolean safeConcurrencySafe(Tool t, JsonNode empty) {
        try { return t.isConcurrencySafe(empty); } catch (Exception e) { return false; }
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
                new DomainSpec("xhs_fetch", "小红书公开数据 (TikHub)",
                        "博主资料/笔记/搜索/视频/评论统一走 TikHub 公开 API。有 userId/主页链接直接用，"
                                + "没有就先 xhs_search_users 拿 userId。",
                        List.of("tikhub_creator_import", "xhs_search_users", "xhs_user_notes",
                                "xhs_search_notes", "xhs_note_detail", "xhs_note_comments",
                                "xhs_hot_list", "xhs_trending", "xhs_refresh_creator",
                                "xhs_third_party_")),
                new DomainSpec("pgy_brand", "蒲公英品牌侧 (PGY)",
                        "蒲公英 KOL 列表/粉丝画像/报价。调前先 xhs_pgy_whoami 确认是品牌主/机构账号；"
                                + "若 cookie 全部失效用 xhs_qr_login_start 让用户去前端扫码补 cookie。",
                        List.of("xhs_fetch_pgy_kol", "xhs_pgy_kol_detail", "xhs_pgy_whoami",
                                "xhs_cookie_list", "xhs_qr_login_start")),
                new DomainSpec("spotlight_data", "聚光广告数据（自家账户）",
                        "查自家广告账户余额 / 计划 / 单元 / 投放报表；不是博主数据",
                        List.of("spotlight_balance_info", "spotlight_campaign_list",
                                "spotlight_unit_list", "spotlight_report_offline_advertiser",
                                "spotlight_oauth_")),
                new DomainSpec("spotlight_planning", "聚光投放工具（关键词/定向/人群）",
                        "创建广告计划/单元前的选词、行业类目、词包、定向、人群预估与重名校验",
                        List.of("spotlight_keyword_", "spotlight_industry_",
                                "spotlight_word_bag_list", "spotlight_target_",
                                "spotlight_crowd_estimate", "spotlight_name_dup_check")),
                new DomainSpec("creator", "创作者库", "博主库增删改查、批量录入",
                        List.of("creator_", "creator.get_posts")),
                new DomainSpec("project_roster", "项目名册", "项目下已选博主的批量管理",
                        List.of("project_roster_")),
                new DomainSpec("schedule", "任务调度", "定时任务 CRUD",
                        List.of("schedule_")),
                new DomainSpec("knowledge", "知识库与搜索", "内部 RAG / 外部联网",
                        List.of("knowledge_search", "web_search", "web_fetch")),
                new DomainSpec("agent_control", "对话与流程", "反问用户、TODO、技能、休眠、自我扩展",
                        List.of("ask_user_question", "todo_write", "list_skills", "use_skill", "skill_upsert",
                                "sleep", "tool_search")),
                new DomainSpec("system", "系统与运维", "只读系统状态 + ADMIN 写配置（LLM key/数据源开关等）",
                        List.of("system_status", "system_config_")),
                new DomainSpec("filesystem", "文件系统（沙箱）", "读写会话沙箱文件、检索 skill 资料、执行受限 bash",
                        List.of("fs_read", "fs_write", "fs_edit", "fs_glob", "fs_grep", "bash"))
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
        JsonNode empty = mapper.createObjectNode();
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
            entry.put("userFacingName", safeUserFacingName(t, empty));
            entry.put("description", t.description());
            entry.put("readOnly", safeReadOnly(t, empty));
            entry.put("destructive", safeDestructive(t, empty));
            // PR2: 让前端能力中心实时知道某工具是否被 feature flag 放行
            entry.put("enabled", toolRegistry.isToolAllowed(t.name()));
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

    /** PR2：列出所有数据源 / 能力 feature flag 当前状态。 */
    @GetMapping("/feature-flags")
    public ResponseEntity<?> listFeatureFlags(@RequestHeader("Authorization") String auth) {
        // 任何已登录用户都能看；写入要 admin / 管理员角色——按需可在路由层加 @PreAuthorize
        try {
            resolveUserId(auth);
        } catch (Exception e) {
            return ResponseEntity.status(401).body(errorBody(401, "Invalid token"));
        }
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", featureFlags.listAll()));
    }

    /** PR2：切换某个 feature flag。{@code enabled=null} 时清除 override，回退到 yml 默认。 */
    @PutMapping("/feature-flags/{key}")
    public ResponseEntity<?> setFeatureFlag(@RequestHeader("Authorization") String auth,
                                            @PathVariable("key") String key,
                                            @RequestBody Map<String, Object> body) {
        try {
            resolveUserId(auth);
        } catch (Exception e) {
            return ResponseEntity.status(401).body(errorBody(401, "Invalid token"));
        }
        Object enabledRaw = body.get("enabled");
        try {
            if (enabledRaw == null) {
                featureFlags.clearOverride(key);
            } else {
                boolean enabled = Boolean.TRUE.equals(enabledRaw)
                        || "true".equalsIgnoreCase(String.valueOf(enabledRaw));
                featureFlags.setEnabled(key, enabled);
            }
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(404).body(errorBody(404, iae.getMessage()));
        }
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", featureFlags.listAll()));
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
    public ResponseEntity<?> getTodos(@RequestHeader("Authorization") String auth,
                                      @PathVariable("scope") String scope) {
        // 鉴权 + 越权防御：
        // TODO 存在 Redis key "agent:todo:{scope}" 下，历史上任意登录用户都可读任意 scope，
        // 会导致他人任务列表（可能含内部敏感信息）泄露。此处强制 scope 绑定到当前 userId 或
        // 当前 userId 拥有的 sessionId。
        Long userId;
        try {
            userId = resolveUserId(auth);
        } catch (Exception e) {
            return ResponseEntity.status(401).body(errorBody(401, "Invalid token"));
        }
        if (!isScopeAllowed(scope, userId)) {
            return ResponseEntity.status(403).body(errorBody(403, "禁止访问该 TODO scope"));
        }
        String raw = redis.opsForValue().get("agent:todo:" + scope);
        if (raw == null) {
            return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", mapper.createArrayNode()));
        }
        try {
            JsonNode node = mapper.readTree(raw);
            return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", node));
        } catch (Exception e) {
            // 注意：Map.of 不允许 value=null，原实现走到这里会二次 NPE。
            return ResponseEntity.ok(errorBody(500, "invalid todo json: " + e.getMessage()));
        }
    }

    private Long resolveUserId(String auth) {
        String token = auth == null ? "" : auth.replace("Bearer ", "");
        String uid = jwtUtils.extractUserIdFromToken(token);
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("无效 token");
        return userResolver.resolve(uid).getId();
    }

    /**
     * scope 合法形式与 {@code TodoWriteTool#buildKey} 对齐：
     * <ul>
     *     <li>{@code user:{userId}}：只允许当前用户读自己</li>
     *     <li>纯数字 sessionId：session 必须归当前用户</li>
     * </ul>
     */
    private boolean isScopeAllowed(String scope, Long userId) {
        if (scope == null || scope.isBlank()) return false;
        if (scope.startsWith("user:")) {
            return scope.equals("user:" + userId);
        }
        try {
            long sid = Long.parseLong(scope);
            return chatSessionRepository.findByIdAndUserId(sid, userId).isPresent();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Map<String, Object> errorBody(int code, String message) {
        Map<String, Object> body = new HashMap<>(3);
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        return body;
    }
}
