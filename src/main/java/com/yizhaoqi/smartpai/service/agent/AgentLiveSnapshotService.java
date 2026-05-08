package com.yizhaoqi.smartpai.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 活体会话快照（用 Redis 存）。
 *
 * <p>背景：Agent 一轮对话期间，{@link AgentEventPublisher} 把 LLM chunk / 工具调用 / step / askUser
 * 等事件实时推 WS。这些数据落库要等 {@link com.yizhaoqi.smartpai.service.agent.memory.MessageStore#appendTurn}
 * 一次性把整轮写完。中途用户切走或刷新，再进会话只能 GET /messages 拉历史，**进行中的内容根本没**
 * （因为还没 appendTurn）。
 *
 * <p>本服务把 Publisher 推过的事件按会话维度同步累积进 Redis。新增 REST {@code GET .../sessions/{id}/live}
 * 让前端在 loadHistory 之后取这份快照拼到尾部，并继续接 WS chunk —— 真正的"刷新即续接"。
 *
 * <p>Key 设计：{@code agent:session:live:{sessionId}}，单个 JSON 对象覆盖式写入。TTL 30 分钟，
 * runTurn 结束（completion / error / cancel）显式清理。
 *
 * <p>线程安全：每会话一个 key；同一会话同时只可能有一个活跃 turn（receivers 是单连接），
 * 因此用全量覆盖即可，不需要 Lua 增量。
 */
@Service
public class AgentLiveSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(AgentLiveSnapshotService.class);
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String KEY_PREFIX = "agent:session:live:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AgentLiveSnapshotService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    private static String keyOf(Long sessionId) { return KEY_PREFIX + sessionId; }

    /** 拉当前会话的活体快照；没有正在进行的 turn 时返回 null。 */
    public LiveSnapshot read(Long sessionId) {
        if (sessionId == null) return null;
        String raw = redis.opsForValue().get(keyOf(sessionId));
        if (raw == null || raw.isBlank()) return null;
        try {
            return mapper.readValue(raw, LiveSnapshot.class);
        } catch (Exception e) {
            logger.warn("解析 live snapshot 失败 sessionId={} err={}", sessionId, e.getMessage());
            redis.delete(keyOf(sessionId));
            return null;
        }
    }

    /** runTurn 一开始：登记 messageId，新建快照 */
    public void start(Long sessionId, String messageId) {
        if (sessionId == null || messageId == null) return;
        LiveSnapshot snap = new LiveSnapshot();
        snap.sessionId = sessionId;
        snap.messageId = messageId;
        snap.startedAt = System.currentTimeMillis();
        snap.lastUpdatedAt = snap.startedAt;
        snap.status = "running";
        snap.partialContent = "";
        snap.toolCalls = new ArrayList<>();
        snap.todos = new ArrayList<>();
        write(snap);
    }

    public void appendChunk(Long sessionId, String messageId, String delta) {
        if (sessionId == null || delta == null || delta.isEmpty()) return;
        LiveSnapshot snap = read(sessionId);
        if (snap == null) snap = bootstrap(sessionId, messageId);
        snap.partialContent = (snap.partialContent == null ? "" : snap.partialContent) + delta;
        snap.lastUpdatedAt = System.currentTimeMillis();
        write(snap);
    }

    public void setStep(Long sessionId, String messageId, Integer step) {
        if (sessionId == null) return;
        LiveSnapshot snap = read(sessionId);
        if (snap == null) snap = bootstrap(sessionId, messageId);
        snap.currentStep = step;
        snap.lastUpdatedAt = System.currentTimeMillis();
        write(snap);
    }

    public void recordToolCall(Long sessionId, String messageId, String toolUseId, Tool tool, Object input) {
        if (sessionId == null || toolUseId == null) return;
        LiveSnapshot snap = read(sessionId);
        if (snap == null) snap = bootstrap(sessionId, messageId);
        ToolCallSnapshot existing = findCall(snap, toolUseId);
        if (existing == null) {
            existing = new ToolCallSnapshot();
            existing.toolUseId = toolUseId;
            existing.startedAt = System.currentTimeMillis();
            snap.toolCalls.add(existing);
        }
        existing.tool = tool == null ? null : tool.name();
        try {
            if (tool != null) existing.userFacingName = tool.userFacingName(asJsonNode(input));
            if (tool != null) existing.readOnly = tool.isReadOnly(asJsonNode(input));
            if (tool != null) existing.summary = tool.summarizeInvocation(asJsonNode(input));
        } catch (Exception ignored) {
            /* tool.userFacingName 抛异常不影响快照 */
        }
        existing.input = input;
        existing.status = "running";
        snap.lastUpdatedAt = System.currentTimeMillis();
        write(snap);
    }

    public void recordToolProgress(Long sessionId, String toolUseId, String text) {
        if (sessionId == null || toolUseId == null) return;
        LiveSnapshot snap = read(sessionId);
        if (snap == null) return;
        ToolCallSnapshot c = findCall(snap, toolUseId);
        if (c == null) return;
        c.progressText = text;
        snap.lastUpdatedAt = System.currentTimeMillis();
        write(snap);
    }

    public void recordToolResult(Long sessionId, String toolUseId, String toolName,
                                 ToolResult result, long durationMs, String previewText) {
        if (sessionId == null || toolUseId == null) return;
        LiveSnapshot snap = read(sessionId);
        if (snap == null) return;
        ToolCallSnapshot c = findCall(snap, toolUseId);
        if (c == null) {
            c = new ToolCallSnapshot();
            c.toolUseId = toolUseId;
            c.startedAt = System.currentTimeMillis();
            snap.toolCalls.add(c);
        }
        c.tool = toolName == null ? c.tool : toolName;
        c.status = result.isError() ? "error" : "ok";
        c.summary = result.summary();
        c.preview = previewText;
        c.isError = result.isError();
        c.durationMs = durationMs;
        c.meta = result.meta();
        c.finishedAt = System.currentTimeMillis();
        c.progressText = null;
        snap.lastUpdatedAt = System.currentTimeMillis();
        write(snap);
    }

    public void recordTodos(Long sessionId, Object todos) {
        if (sessionId == null) return;
        LiveSnapshot snap = read(sessionId);
        if (snap == null) return;
        if (todos instanceof List<?> list) {
            snap.todos = new ArrayList<>(list);
        }
        snap.lastUpdatedAt = System.currentTimeMillis();
        write(snap);
    }

    public void recordAskUser(Long sessionId, String question, List<String> options) {
        if (sessionId == null) return;
        LiveSnapshot snap = read(sessionId);
        if (snap == null) return;
        AskUserSnapshot a = new AskUserSnapshot();
        a.question = question;
        a.options = options == null ? List.of() : List.copyOf(options);
        a.askedAt = System.currentTimeMillis();
        snap.askUser = a;
        snap.lastUpdatedAt = a.askedAt;
        write(snap);
    }

    /** runTurn 结束：清掉快照（前端继续靠 history）。 */
    public void clear(Long sessionId) {
        if (sessionId == null) return;
        try {
            redis.delete(keyOf(sessionId));
        } catch (Exception e) {
            logger.debug("clear live snapshot 失败 sessionId={} err={}", sessionId, e.getMessage());
        }
    }

    // --------------------- 内部 ---------------------

    private LiveSnapshot bootstrap(Long sessionId, String messageId) {
        LiveSnapshot snap = new LiveSnapshot();
        snap.sessionId = sessionId;
        snap.messageId = messageId;
        snap.startedAt = System.currentTimeMillis();
        snap.lastUpdatedAt = snap.startedAt;
        snap.status = "running";
        snap.partialContent = "";
        snap.toolCalls = new ArrayList<>();
        snap.todos = new ArrayList<>();
        return snap;
    }

    private ToolCallSnapshot findCall(LiveSnapshot snap, String toolUseId) {
        if (snap.toolCalls == null) return null;
        for (ToolCallSnapshot c : snap.toolCalls) {
            if (toolUseId.equals(c.toolUseId)) return c;
        }
        return null;
    }

    private com.fasterxml.jackson.databind.JsonNode asJsonNode(Object input) {
        if (input == null) return mapper.createObjectNode();
        if (input instanceof com.fasterxml.jackson.databind.JsonNode n) return n;
        return mapper.valueToTree(input);
    }

    private void write(LiveSnapshot snap) {
        try {
            redis.opsForValue().set(keyOf(snap.sessionId), mapper.writeValueAsString(snap), TTL);
        } catch (Exception e) {
            logger.warn("写 live snapshot 失败 sessionId={} err={}", snap.sessionId, e.getMessage());
        }
    }

    // --------------------- DTO ---------------------

    public static class LiveSnapshot {
        public Long sessionId;
        public String messageId;
        public long startedAt;
        public long lastUpdatedAt;
        /** running / completed / error / cancelled / stopped */
        public String status;
        public String partialContent;
        public Integer currentStep;
        public List<ToolCallSnapshot> toolCalls = new ArrayList<>();
        public List<Object> todos = new ArrayList<>();
        public AskUserSnapshot askUser;
    }

    public static class ToolCallSnapshot {
        public String toolUseId;
        public String tool;
        public String userFacingName;
        public Boolean readOnly;
        public String summary;
        public Object input;
        /** running / ok / error */
        public String status;
        public String progressText;
        public String preview;
        public Boolean isError;
        public Long durationMs;
        public Map<String, Object> meta;
        public Long startedAt;
        public Long finishedAt;
    }

    public static class AskUserSnapshot {
        public String question;
        public List<String> options;
        public long askedAt;
    }

    @SuppressWarnings("unused")
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @SuppressWarnings("unused")
    private static Map<String, Object> emptyMap() { return new LinkedHashMap<>(); }
}
