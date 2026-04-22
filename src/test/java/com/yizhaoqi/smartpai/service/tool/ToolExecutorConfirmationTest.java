package com.yizhaoqi.smartpai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolExecutor 的 Phase 3b 确认协议覆盖。
 * 用一个本地 FakeTool 代替具体业务工具，只关注执行器自己的拦截逻辑。
 */
class ToolExecutorConfirmationTest {

    private ToolExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        executor = new ToolExecutor();
    }

    @Test
    void firstCallReturnsConfirmationRequiredWithToken() {
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        FakeTool t = new FakeTool("destruct", /*destructive*/ true,
                input -> ConfirmationRequest.of("will do destructive thing", "irreversible"),
                (ctx, in) -> {
                    seen.set(in);
                    return ToolResult.text("done");
                });

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        ToolExecutor.ToolExecution exec = executor.execute(t, in,
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build());

        assertTrue(exec.result().isError(), "首次应返回 confirmation_required error");
        assertNull(seen.get(), "首次不应真正调到 tool.call");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) exec.result().data();
        assertEquals(Boolean.TRUE, data.get("confirmation_required"));
        assertEquals("destruct", data.get("tool"));
        assertNotNull(data.get("confirmToken"));
        assertTrue(((String) data.get("howToProceed")).contains("_confirmToken"));
    }

    @Test
    void secondCallWithMatchingTokenProceedsAndStripsReservedKeys() {
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        FakeTool t = new FakeTool("destruct", true,
                input -> ConfirmationRequest.of("do it"),
                (ctx, in) -> {
                    seen.set(in);
                    return ToolResult.text("done");
                });

        // Step 1: get token
        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        ToolExecutor.ToolExecution first = executor.execute(t, in,
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build());
        @SuppressWarnings("unchecked")
        Map<String, Object> firstData = (Map<String, Object>) first.result().data();
        String token = (String) firstData.get("confirmToken");
        assertNotNull(token);

        // Step 2: retry with confirm + token
        ObjectNode retry = mapper.createObjectNode();
        retry.put("id", 7L);
        retry.put("_confirm", true);
        retry.put("_confirmToken", token);
        ToolExecutor.ToolExecution second = executor.execute(t, retry,
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build());

        assertFalse(second.result().isError());
        assertEquals("done", second.result().data());
        // 工具看到的 input 必须不含 _confirm / _confirmToken
        assertFalse(seen.get().has("_confirm"));
        assertFalse(seen.get().has("_confirmToken"));
        assertEquals(7L, seen.get().get("id").asLong());
    }

    @Test
    void tamperedArgsInvalidateToken() {
        FakeTool t = new FakeTool("destruct", true,
                input -> ConfirmationRequest.of("do it"),
                (ctx, in) -> ToolResult.text("done"));

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        ToolExecutor.ToolExecution first = executor.execute(t, in,
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build());
        @SuppressWarnings("unchecked")
        Map<String, Object> firstData = (Map<String, Object>) first.result().data();
        String token = (String) firstData.get("confirmToken");

        // LLM 偷偷把 id 从 7 改成 100，复用上一轮的 token
        ObjectNode tampered = mapper.createObjectNode();
        tampered.put("id", 100L);
        tampered.put("_confirm", true);
        tampered.put("_confirmToken", token);
        ToolExecutor.ToolExecution second = executor.execute(t, tampered,
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build());

        assertTrue(second.result().isError(), "id 被改过 -> token 不匹配 -> 必须再拦一次");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondData = (Map<String, Object>) second.result().data();
        assertEquals(Boolean.TRUE, secondData.get("confirmation_required"));
    }

    @Test
    void nonDestructiveToolIsUnaffectedAndStillStripsReserved() {
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        FakeTool t = new FakeTool("readonly", false,
                input -> null, // no confirmation
                (ctx, in) -> {
                    seen.set(in);
                    return ToolResult.text("done");
                });

        // 有些 LLM 可能习惯性加 _confirm=true 给所有工具；这里要确保被剥离掉
        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        in.put("_confirm", true);
        in.put("_confirmToken", "anything");
        ToolExecutor.ToolExecution exec = executor.execute(t, in,
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build());

        assertFalse(exec.result().isError());
        assertFalse(seen.get().has("_confirm"));
        assertFalse(seen.get().has("_confirmToken"));
    }

    @Test
    void permissionDenyShortCircuitsBeforeConfirmation() {
        FakeTool t = new FakeTool("destruct", true,
                input -> ConfirmationRequest.of("do it"),
                (ctx, in) -> ToolResult.text("done")) {
            @Override
            public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
                return PermissionResult.deny("nope");
            }
        };
        ToolExecutor.ToolExecution exec = executor.execute(t, mapper.createObjectNode(),
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build());

        assertTrue(exec.result().isError());
        // Phase 4b: errorCode 结构化到 meta.errorCode；summary 只保留人话原因
        assertEquals("permission_denied", exec.result().meta().get("errorCode"));
        assertEquals("nope", exec.result().summary(),
                "Phase 4b: summary 应为 deny reason 本身，不再带 permission_denied: 前缀");
    }

    @Test
    void stripReservedKeysHandlesNonObject() throws Exception {
        JsonNode n = mapper.readTree("42");
        ToolExecutor.StrippedInput s = ToolExecutor.stripReservedKeys(n);
        assertFalse(s.hadReservedKeys());
        assertFalse(s.confirm());
    }

    @Test
    void tokenIsStableAcrossEqualInputs() {
        ObjectNode a = mapper.createObjectNode();
        a.put("b", 2);
        a.put("a", 1);
        ObjectNode b = mapper.createObjectNode();
        b.put("a", 1);
        b.put("b", 2);
        assertEquals(ToolExecutor.generateConfirmToken("t", a),
                ToolExecutor.generateConfirmToken("t", b),
                "规范化序列化应保证字段顺序不影响 token");
    }

    // ---------- FakeTool ----------

    @FunctionalInterface
    interface Runner { ToolResult run(ToolContext ctx, JsonNode input); }

    @FunctionalInterface
    interface ConfirmProvider { ConfirmationRequest provide(JsonNode input); }

    static class FakeTool implements Tool {
        private final String n;
        private final boolean destructive;
        private final ConfirmProvider confirmProvider;
        private final Runner runner;
        private final ObjectMapper m = new ObjectMapper();

        FakeTool(String n, boolean destructive, ConfirmProvider c, Runner r) {
            this.n = n;
            this.destructive = destructive;
            this.confirmProvider = c;
            this.runner = r;
        }

        @Override public String name() { return n; }
        @Override public String description() { return n; }
        @Override public JsonNode inputSchema() {
            ObjectNode o = m.createObjectNode();
            o.put("type", "object");
            return o;
        }
        @Override public boolean isDestructive(JsonNode input) { return destructive; }
        @Override public ConfirmationRequest requiresConfirmation(ToolContext ctx, JsonNode input) {
            return confirmProvider.provide(input);
        }
        @Override public ToolResult call(ToolContext ctx, JsonNode input) {
            return runner.run(ctx, input);
        }
    }
}
