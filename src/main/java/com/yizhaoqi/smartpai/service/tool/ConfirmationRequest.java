package com.yizhaoqi.smartpai.service.tool;

import java.util.List;

/**
 * 破坏性工具的二次确认声明。
 *
 * <p>由 {@link Tool#requiresConfirmation(ToolContext, com.fasterxml.jackson.databind.JsonNode)}
 * 返回：非空表示"这次调用必须等用户明确放行才能执行"。{@link ToolExecutor} 见到非空就短路工具调用，
 * 把 {@link #summary}/{@link #reason}/{@link #risks}/{@link #confirmToken} 打包成 {@code confirmation_required}
 * 的 ToolResult 回灌给 LLM，LLM 再走 {@code ask_user_question} 跟用户确认，最后带回
 * {@code _confirm=true} + {@code _confirmToken=<原 token>} 再调一次工具。
 *
 * <h3>为什么要 token？</h3>
 * 防止 LLM 在二次调用时偷偷改掉参数（比如第一次说要删 #7，第二次变成 #100）。
 * token 对参数做规范化哈希，一改参数 token 就对不上，Executor 会拒绝。
 *
 * @param summary      人类可读的一句话说明"要干嘛"。会展示给终端用户。
 *                     例：{@code "将删除 cookie #7 (xhs_pc, label=old)"}
 * @param reason       为什么需要确认。可选。
 * @param confirmToken Runtime 在拦截时生成的确认令牌，对整条 {tool_name + 规范化 input} 做 SHA-256 截断。
 *                     LLM 必须原样回传。
 * @param risks        可选的风险清单，方便 LLM 向用户列出后果。
 */
public record ConfirmationRequest(
        String summary,
        String reason,
        String confirmToken,
        List<String> risks) {

    public static ConfirmationRequest of(String summary) {
        return new ConfirmationRequest(summary, null, null, List.of());
    }

    public static ConfirmationRequest of(String summary, String reason) {
        return new ConfirmationRequest(summary, reason, null, List.of());
    }

    public static ConfirmationRequest of(String summary, String reason, List<String> risks) {
        return new ConfirmationRequest(summary, reason, null,
                risks == null ? List.of() : List.copyOf(risks));
    }

    /** Executor 在检测后把生成的 token 注入，不改 summary/reason/risks。 */
    public ConfirmationRequest withToken(String token) {
        return new ConfirmationRequest(summary, reason, token, risks);
    }
}
