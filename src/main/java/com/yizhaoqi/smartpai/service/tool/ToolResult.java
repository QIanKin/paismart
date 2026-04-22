package com.yizhaoqi.smartpai.service.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具的执行结果。
 * <ul>
 *   <li>{@code data} 是要回灌给 LLM 的载荷（会被序列化为 JSON 放进 tool 消息 content）；</li>
 *   <li>{@code summary} 是人类可读的一句话摘要，Runtime 会发到 WS tool_result.summary 字段展示；</li>
 *   <li>{@code meta} 留作扩展（记录扫描了多少条文档、花了多少 ms、外链 URL、
 *       <b>Phase 4b 新加 {@code errorCode}</b> 供前端映射帮助链接）。</li>
 * </ul>
 *
 * <p>设计原则参考 claude-code agent-tools：LLM 看到的是 {@code data}；UI 展示的是 {@code summary + meta}。
 * 两者分开是为了避免在把结构化结果"拍扁"给 UI 时污染 LLM 上下文。
 *
 * <h3>Phase 4b: 错误码 / 人话分离</h3>
 * 旧写法 {@code error("cookie_invalid: a1 为空")} 会让前端 summary 里直接出现 {@code "cookie_invalid: a1 为空"}，
 * 既对非开发用户不友好，又没法做"错误码 → 帮助链接"的映射。
 *
 * <p>新推荐写法：
 * <pre>
 *   return ToolResult.error("cookie_invalid", "缺少必须字段 a1，请重新扫码登录");
 * </pre>
 * 等价于：
 * <ul>
 *   <li>data = {error:true, code:"cookie_invalid", message:"缺少必须字段 a1..."}</li>
 *   <li>summary = "缺少必须字段 a1..."（不再有 code 前缀）</li>
 *   <li>meta = {errorCode:"cookie_invalid"}</li>
 * </ul>
 *
 * <p>旧的 {@link #error(String)} 依然保留，并自动把形如 {@code "code: message"} 的前缀拆成结构化
 * {@code errorCode}，让 96+ 未迁移的调用点无痛受益。
 */
public record ToolResult(Object data, String summary, Map<String, Object> meta, boolean isError) {

    /** 识别 "snake_case_code: 人话" 前缀，用于兼容老 error(message) 的字符串。 */
    private static final Pattern LEGACY_CODE_PREFIX =
            Pattern.compile("^([a-z][a-z0-9_]{1,39}):\\s+(.+)$", Pattern.DOTALL);

    public static ToolResult of(Object data, String summary) {
        return new ToolResult(data, summary, Map.of(), false);
    }

    public static ToolResult of(Object data, String summary, Map<String, Object> meta) {
        return new ToolResult(data, summary, meta == null ? Map.of() : meta, false);
    }

    public static ToolResult text(String text) {
        return new ToolResult(text, text, Map.of(), false);
    }

    /**
     * 旧签名：{@code message} 若形如 {@code "code: 人话"}，自动拆分 errorCode 进 meta。
     * 纯 message 字符串不受影响。
     */
    public static ToolResult error(String message) {
        return error(message, (Map<String, Object>) null);
    }

    public static ToolResult error(String message, Map<String, Object> extra) {
        String code = null;
        String humanMessage = message;
        if (message != null) {
            Matcher m = LEGACY_CODE_PREFIX.matcher(message);
            if (m.matches()) {
                code = m.group(1);
                humanMessage = m.group(2);
            }
        }
        return buildError(code, humanMessage, extra);
    }

    /**
     * Phase 4b 推荐签名：显式给 errorCode + 人话分离。
     *
     * @param code          机器可识别的错误码（snake_case，如 {@code cookie_invalid}、
     *                      {@code config_missing}、{@code not_found}）；会进 {@code meta.errorCode}。
     *                      见 {@link ToolErrors} 中的常量。
     * @param humanMessage  给用户看的中文句子，不要再出现 code 前缀。
     */
    public static ToolResult error(String code, String humanMessage) {
        return buildError(code, humanMessage, null);
    }

    public static ToolResult error(String code, String humanMessage, Map<String, Object> extra) {
        return buildError(code, humanMessage, extra);
    }

    private static ToolResult buildError(String code, String humanMessage, Map<String, Object> extra) {
        String message = humanMessage == null ? "" : humanMessage;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", true);
        if (code != null && !code.isBlank()) payload.put("code", code);
        payload.put("message", message);
        if (extra != null && !extra.isEmpty()) {
            for (Map.Entry<String, Object> e : extra.entrySet()) {
                // 不让 extra 覆盖 error/code/message 这些固定字段
                if ("error".equals(e.getKey())) continue;
                if ("code".equals(e.getKey()) && code != null) continue;
                if ("message".equals(e.getKey())) continue;
                payload.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> meta;
        if (code != null && !code.isBlank()) {
            meta = new LinkedHashMap<>();
            meta.put("errorCode", code);
        } else {
            meta = Map.of();
        }
        return new ToolResult(payload, message, meta, true);
    }
}
