package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.xhs.XhsLoginSession;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsLoginSessionService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * xhs_qr_login_start：当 Agent 发现 PGY cookie 全部失效时，启动一个扫码登录会话，
 * 把 sessionId 抛回给前端 / 用户，让用户点开数据源中心→扫码登录采集那个按钮 完成扫码。
 *
 * <p>注意：本工具自己不会扫二维码（LLM 没有手机）。它的作用是：
 * <ol>
 *   <li>在后端 DB 落一条 PENDING session、拉起 Chromium 子进程开始采二维码；</li>
 *   <li>把 {@code sessionId} 和 WebSocket 订阅地址告诉用户，让用户去前端扫；</li>
 *   <li>同时让 Agent 知道凭证流转已经启动，可以告诉用户"我已经帮你打开扫码会话了"。</li>
 * </ol>
 *
 * <p>更推荐的做法：让 Agent 调 {@code xhs_cookie_list} 看一下 cookie 池状态后，
 * 直接给用户"请去数据源中心→蒲公英 tab 扫码登录"的提示，不一定非要主动起 session。
 */
@Component
public class XhsQrLoginStartTool implements Tool {

    private static final String DEFAULT_PLATFORM = "xhs_pgy";

    private final XhsLoginSessionService loginService;
    private final JsonNode schema;

    public XhsQrLoginStartTool(XhsLoginSessionService loginService) {
        this.loginService = loginService;
        this.schema = ToolInputSchemas.object()
                .stringProp("note",
                        "可选：扫码理由，例如 '蒲公英 cookie 失效，请扫码补一条'，会展示给用户",
                        false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_qr_login_start"; }

    @Override public String description() {
        return "启动一次蒲公英扫码登录会话，返回 sessionId 让前端订阅二维码。"
                + "调用本工具不代表 AI 真的会扫码——LLM 没有手机，必须由用户在数据源中心点 \"扫码登录采集\" 按钮 / 用手机扫二维码。"
                + "适合场景：xhs_cookie_list 显示 ACTIVE=0 时，让用户尽快去扫码。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String userId = ctx.userId();
        if (userId == null || userId.isBlank()) {
            return ToolResult.error("missing_user", "无法从会话上下文取到 userId，无法启动扫码会话");
        }

        XhsLoginSession session;
        try {
            session = loginService.start(ctx.orgTag(), userId, List.of(DEFAULT_PLATFORM));
        } catch (Exception e) {
            return ToolResult.error("start_failed", "扫码会话启动失败: " + e.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getSessionId());
        data.put("platforms", List.of(DEFAULT_PLATFORM));
        data.put("status", session.getStatus() == null ? null : session.getStatus().name());
        data.put("expiresAt", session.getExpiresAt());
        data.put("frontendHint", "请打开「数据源中心 → 蒲公英 · Cookie → 扫码登录采集」并用手机扫码完成");

        String userNote = input.path("note").asText("");
        String summary = userNote.isBlank()
                ? "已启动扫码会话 " + session.getSessionId() + "，请用户在前端扫码"
                : userNote + "（扫码会话已启动，sessionId=" + session.getSessionId() + "）";
        return ToolResult.of(data, summary);
    }
}
