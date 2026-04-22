package com.yizhaoqi.smartpai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具执行器：把"权限 -> 确认 -> 输入解析 -> 实际 call -> 异常兜底 -> 计时/日志"统一收敛。
 * Agent 主循环只和本类打交道，不直接碰 Tool.call()。
 *
 * <h3>确认协议（Phase 3b）</h3>
 * 破坏性工具通过覆盖 {@link Tool#requiresConfirmation} 声明"本次调用要等用户放行"。本执行器：
 * <ol>
 *   <li>先剥离保留字段 {@value #KEY_CONFIRM}、{@value #KEY_TOKEN} 得到"业务 input"；</li>
 *   <li>对 (toolName + 规范化业务 input) 做 SHA-256 生成 expectedToken；</li>
 *   <li>调 {@code requiresConfirmation(ctx, strippedInput)}：
 *     <ul>
 *       <li>返回 null：走正常路径，input 会是剥离后的版本，工具看不到 _confirm/_confirmToken。</li>
 *       <li>返回非 null：检查调用方是否传了 _confirm=true 且 _confirmToken 匹配 expectedToken；
 *           匹配则放行，不匹配则短路返回 {@code confirmation_required} 的 {@link ToolResult}。</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Component
public class ToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** LLM 二次调用时必须带的"我已确认"标记。 */
    public static final String KEY_CONFIRM = "_confirm";
    /** LLM 二次调用时必须原样回传的令牌。 */
    public static final String KEY_TOKEN = "_confirmToken";

    /**
     * 执行单个工具。任何异常都会被捕获并封装成 ToolResult.error，绝不向上抛，
     * 因为 LLM 需要从"工具失败"中自愈。
     */
    public ToolExecution execute(Tool tool, JsonNode rawInput, ToolContext ctx) {
        long start = System.currentTimeMillis();
        JsonNode input = rawInput == null ? NullNode.getInstance() : rawInput;

        try {
            PermissionResult perm = tool.checkPermission(ctx, input);
            if (perm instanceof PermissionResult.Deny deny) {
                logger.warn("Tool [{}] 权限拒绝: user={}, reason={}",
                        tool.name(), ctx.userId(), deny.reason());
                ToolResult res = ToolResult.error(ToolErrors.PERMISSION_DENIED, deny.reason());
                return new ToolExecution(tool, input, res, System.currentTimeMillis() - start);
            }
            if (perm instanceof PermissionResult.Allow allow && allow.rewrittenInput() != null) {
                input = allow.rewrittenInput();
            }

            if (ctx.isCancelled()) {
                return new ToolExecution(tool, input,
                        ToolResult.error("cancelled_by_user", "操作已被用户取消"),
                        System.currentTimeMillis() - start);
            }

            // ---- Phase 3b: 剥离确认保留字段 + 二次确认拦截 ----
            StrippedInput stripped = stripReservedKeys(input);
            ConfirmationRequest confirmReq = tool.requiresConfirmation(ctx, stripped.business);
            if (confirmReq != null) {
                String expectedToken = generateConfirmToken(tool.name(), stripped.business);
                boolean confirmed = stripped.confirm
                        && expectedToken.equals(stripped.confirmToken);
                if (!confirmed) {
                    logger.info("Tool [{}] 需要二次确认: user={}, expectedToken={}, provided={}",
                            tool.name(), ctx.userId(), expectedToken, stripped.confirmToken);
                    ToolResult res = buildConfirmationRequired(tool, confirmReq, expectedToken, stripped.business);
                    return new ToolExecution(tool, input, res, System.currentTimeMillis() - start);
                }
                // 通过 -> 后续 tool.call 只看到业务 input
                input = stripped.business;
            } else if (stripped.hadReservedKeys) {
                // 工具本身不要求确认，也把保留字段剥掉，别污染 tool.call 的参数
                input = stripped.business;
            }

            ToolResult res = tool.call(ctx, input);
            if (res == null) {
                res = ToolResult.text("(tool returned null)");
            }
            long cost = System.currentTimeMillis() - start;
            logger.info("Tool [{}] 完成 cost={}ms isError={} summary={}",
                    tool.name(), cost, res.isError(), res.summary());
            return new ToolExecution(tool, input, res, cost);
        } catch (Throwable ex) {
            long cost = System.currentTimeMillis() - start;
            logger.error("Tool [{}] 执行失败 cost={}ms user={}", tool.name(), cost, ctx.userId(), ex);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("exception", ex.getClass().getSimpleName());
            ToolResult res = ToolResult.error(ToolErrors.INTERNAL, safeMessage(ex), extra);
            return new ToolExecution(tool, input, res, cost);
        }
    }

    /**
     * 把 ToolResult.data 序列化为字符串，便于以 OpenAI tool message 形式回灌给 LLM。
     */
    public String resultToLlmPayload(ToolResult result) {
        if (result == null) {
            return "";
        }
        try {
            Object data = result.data();
            if (data == null) {
                return "";
            }
            if (data instanceof String s) {
                return s;
            }
            return MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            logger.warn("序列化 ToolResult.data 失败", e);
            return String.valueOf(result.data());
        }
    }

    private String safeMessage(Throwable ex) {
        String msg = ex.getMessage();
        if (msg != null && !msg.isBlank()) return msg;
        return ex.getClass().getSimpleName();
    }

    // ---------- 确认协议内部实现 ----------

    /**
     * 剥离 _confirm / _confirmToken，返回业务 input 和保留字段值。
     * 非 ObjectNode（例如 null/数组）直接原样返回，confirm=false/token=null。
     */
    static StrippedInput stripReservedKeys(JsonNode input) {
        if (input == null || !input.isObject()) {
            return new StrippedInput(input == null ? NullNode.getInstance() : input,
                    false, null, false);
        }
        ObjectNode src = (ObjectNode) input;
        boolean hadConfirm = src.has(KEY_CONFIRM);
        boolean hadToken = src.has(KEY_TOKEN);
        if (!hadConfirm && !hadToken) {
            return new StrippedInput(src, false, null, false);
        }
        boolean confirm = hadConfirm && src.get(KEY_CONFIRM).asBoolean(false);
        String token = hadToken ? textOrNull(src.get(KEY_TOKEN)) : null;
        ObjectNode copy = src.deepCopy();
        copy.remove(KEY_CONFIRM);
        copy.remove(KEY_TOKEN);
        return new StrippedInput(copy, confirm, token, true);
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        String s = n.asText(null);
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * 生成 confirmToken：SHA-256( toolName + "|" + 规范化 JSON(input) )，取前 16 字节 base64。
     * 同一工具+同一输入 -> 同一 token。LLM 改参数 -> token 失效。
     *
     * <p>Jackson 的 {@code ORDER_MAP_ENTRIES_BY_KEYS} 不适用于 {@code ObjectNode}——它只对 Java
     * {@code Map} 有效。所以这里手写一个递归 canonicalizer：object 字段按字典序、array 保持原序、
     * 数字一律 {@code asText()} 去掉科学计数法差异，保证"语义等价 → 字节等价"。
     */
    static String generateConfirmToken(String toolName, JsonNode businessInput) {
        try {
            StringBuilder sb = new StringBuilder();
            writeCanonical(sb, businessInput == null ? NullNode.getInstance() : businessInput);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] raw = md.digest((toolName + "|" + sb).getBytes(StandardCharsets.UTF_8));
            byte[] head = new byte[16];
            System.arraycopy(raw, 0, head, 0, 16);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(head);
        } catch (Exception e) {
            logger.error("生成 confirmToken 失败 tool={}", toolName, e);
            return "ERROR-" + System.nanoTime();
        }
    }

    private static void writeCanonical(StringBuilder sb, JsonNode node) {
        if (node == null || node.isNull()) { sb.append("null"); return; }
        if (node.isBoolean()) { sb.append(node.asBoolean() ? "true" : "false"); return; }
        if (node.isNumber()) {
            // 规范化为 toPlainString，避免 1e2 vs 100 出现差异
            if (node.isIntegralNumber()) sb.append(node.asLong());
            else sb.append(node.decimalValue().toPlainString());
            return;
        }
        if (node.isTextual()) {
            sb.append('"').append(escape(node.textValue())).append('"');
            return;
        }
        if (node.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) sb.append(',');
                writeCanonical(sb, child);
                first = false;
            }
            sb.append(']');
            return;
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            sb.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(',');
                sb.append('"').append(escape(k)).append('"').append(':');
                writeCanonical(sb, node.get(k));
                first = false;
            }
            sb.append('}');
            return;
        }
        // 兜底：未知节点按 text
        sb.append('"').append(escape(node.asText())).append('"');
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private ToolResult buildConfirmationRequired(Tool tool,
                                                  ConfirmationRequest req,
                                                  String expectedToken,
                                                  JsonNode businessInput) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("confirmation_required", true);
        data.put("tool", tool.name());
        data.put("summary", req.summary());
        data.put("reason", req.reason());
        data.put("risks", req.risks() == null ? java.util.List.of() : req.risks());
        data.put("confirmToken", expectedToken);
        data.put("howToProceed", "用 ask_user_question 跟用户讲清 summary/risks 拿到同意后，"
                + "再次调用 " + tool.name() + " 时带上 _confirm=true 和 _confirmToken=\"" + expectedToken + "\"。"
                + "禁止绕过——修改业务参数会让 _confirmToken 失效。");
        String humanSummary = "需要确认：" + (req.summary() == null ? tool.name() : req.summary());
        return ToolResult.error(ToolErrors.CONFIRMATION_REQUIRED, humanSummary, data);
    }

    public ObjectNode describeForWs(ToolExecution exec) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tool", exec.tool().name());
        node.put("toolUseId", exec.contextSnapshotToolUseId());
        node.set("input", exec.input());
        node.put("isError", exec.result().isError());
        node.put("summary", exec.result().summary());
        node.put("durationMs", exec.durationMs());
        return node;
    }

    public record ToolExecution(Tool tool, JsonNode input, ToolResult result, long durationMs) {
        public String contextSnapshotToolUseId() {
            // 由调用者在构造 ToolContext 时注入 toolUseId；这里只是保留字段给 describeForWs 用。
            return null;
        }
    }

    /** 内部剥离结果。{@code business} 永远不含保留字段，可以直接传 {@link Tool#call}。 */
    record StrippedInput(JsonNode business, boolean confirm, String confirmToken, boolean hadReservedKeys) {}
}
