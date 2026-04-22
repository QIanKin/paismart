package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.xhs.XhsLoginSession;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsLoginSessionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code xhs_qr_login_start}：触发一次小红书扫码登录会话，让前端开始订阅二维码流。
 *
 * <p>内部包装 {@link XhsLoginSessionService#start}：
 * <ul>
 *   <li>在数据库落一条 PENDING 的 {@link XhsLoginSession}，返回 sessionId</li>
 *   <li>同时拉起 node 子进程跑扫码脚本，不阻塞——二维码稍后通过 WS 推给前端</li>
 *   <li>扫码成功后会自动把捕获到的多平台 cookie 批量 upsert 回 cookie 池</li>
 * </ul>
 *
 * <p>返回 sessionId 后 agent 可以：
 * <ol>
 *   <li>把 sessionId 回复给用户，让用户在前端 /data-sources 页面订阅这条会话</li>
 *   <li>后续若需要自检进度，用 xhs_cookie_list / xhs_cookie_ping 判断新记录是否落库</li>
 * </ol>
 *
 * <p>权限：仅管理员。属于破坏性（会启动子进程 + 未来会写入 cookie 池）。
 */
@Component
public class XhsQrLoginStartTool implements Tool {

    private static final List<String> ALL_PLATFORMS = List.of(
            "xhs_pc", "xhs_creator", "xhs_pgy", "xhs_qianfan");

    private final XhsLoginSessionService loginService;
    private final JsonNode schema;

    public XhsQrLoginStartTool(XhsLoginSessionService loginService) {
        this.loginService = loginService;
        var itemSchema = ToolInputSchemas.mapper().createObjectNode();
        itemSchema.put("type", "string");
        itemSchema.putArray("enum")
                .add("xhs_pc").add("xhs_creator").add("xhs_pgy").add("xhs_qianfan");
        this.schema = ToolInputSchemas.object()
                .arrayProp("platforms",
                        "要一次性采集的平台列表。留空 = 跟 application.yml 的 default-platforms。"
                                + "常规 MCN 场景建议全部 4 个都采。",
                        itemSchema,
                        false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_qr_login_start"; }

    @Override public String description() {
        return "触发一次小红书扫码登录会话，返回 sessionId 和过期时间；扫码采到的 cookie 会自动入 cookie 池。"
                + "适合 agent 发现 xhs_pc/creator/pgy/qianfan 全部 EXPIRED、但 spotlight 还在（或反之）时"
                + "自动提示用户扫码续命。仅管理员可用。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return false; }

    @Override public boolean isDestructive(JsonNode input) { return true; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        if (!"admin".equalsIgnoreCase(ctx.role())) {
            return PermissionResult.deny("xhs_qr_login_start 仅管理员可用，当前 role=" + ctx.role());
        }
        if (ctx.orgTag() == null || ctx.orgTag().isBlank()) {
            return PermissionResult.deny("缺少 orgTag，拒绝");
        }
        if (ctx.userId() == null || ctx.userId().isBlank()) {
            return PermissionResult.deny("缺少 userId，无法 single-flight");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        List<String> platforms = new ArrayList<>();
        if (input.has("platforms") && input.get("platforms").isArray()) {
            for (JsonNode n : input.get("platforms")) {
                String p = n.asText("");
                if (!p.isBlank() && ALL_PLATFORMS.contains(p)) platforms.add(p);
            }
        }

        XhsLoginSession s;
        try {
            s = loginService.start(ctx.orgTag(), ctx.userId(), platforms.isEmpty() ? null : platforms);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("bad_request: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("internal: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", s.getSessionId());
        data.put("status", s.getStatus() == null ? null : s.getStatus().name());
        data.put("platforms", s.getPlatforms());
        data.put("startedAt", s.getStartedAt() == null ? null : s.getStartedAt().toString());
        data.put("expiresAt", s.getExpiresAt() == null ? null : s.getExpiresAt().toString());
        data.put("wsHint", "前端可以订阅 ws://<host>/api/v1/xhs/login/ws?sessionId=" + s.getSessionId()
                + " 来拿二维码 dataUrl、状态变化和最终 success 事件");
        String summary = String.format("xhs_qr_login_start → session=%s status=%s platforms=%s",
                s.getSessionId(), s.getStatus(), s.getPlatforms());
        return ToolResult.of(data, summary);
    }
}
