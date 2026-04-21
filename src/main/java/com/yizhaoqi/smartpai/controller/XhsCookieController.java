package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.config.XhsLoginProperties;
import com.yizhaoqi.smartpai.model.xhs.XhsLoginSession;
import com.yizhaoqi.smartpai.service.agent.AgentUserResolver;
import com.yizhaoqi.smartpai.service.xhs.CookieCipher;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import com.yizhaoqi.smartpai.service.xhs.XhsLoginSessionService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小红书/蒲公英/千帆 cookie 池管理。
 *
 * 访问控制：本 controller 的所有端点都挂在 {@code /api/v1/admin/xhs-cookies}，
 * 在 {@link com.yizhaoqi.smartpai.config.SecurityConfig} 里 {@code /api/v1/admin/**} 已限制 ADMIN 角色。
 *
 * 租户隔离：每次操作都以当前 JWT 的 primaryOrg 做 ownerOrgTag；管理员也只能看/改自己所在 org。
 */
@RestController
@RequestMapping("/api/v1/admin/xhs-cookies")
public class XhsCookieController {

    private final XhsCookieService service;
    private final AgentUserResolver userResolver;
    private final JwtUtils jwtUtils;
    private final CookieCipher cipher;
    private final XhsLoginSessionService loginService;
    private final XhsLoginProperties loginProps;

    public XhsCookieController(XhsCookieService service,
                               AgentUserResolver userResolver,
                               JwtUtils jwtUtils,
                               CookieCipher cipher,
                               XhsLoginSessionService loginService,
                               XhsLoginProperties loginProps) {
        this.service = service;
        this.userResolver = userResolver;
        this.jwtUtils = jwtUtils;
        this.cipher = cipher;
        this.loginService = loginService;
        this.loginProps = loginProps;
    }

    @GetMapping
    public ResponseEntity<Object> list(@RequestHeader("Authorization") String auth) {
        User u = resolveUser(auth);
        List<XhsCookie> rows = service.list(u.getPrimaryOrg());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", rows);
        data.put("platforms", XhsCookieService.PLATFORMS);
        data.put("requiredFields", XhsCookieService.REQUIRED_COOKIE_FIELDS);
        data.put("insecureDefault", cipher.isUsingDefaultSecret());
        return ok(data);
    }

    /**
     * 纯预览接口：用户在前端粘了一坨 cookie 字符串，这里告诉它缺/多哪些 key。
     * 不写库、不加密、不落日志（只打 key 不打 value）。
     */
    @PostMapping("/validate")
    public ResponseEntity<Object> validate(@RequestHeader("Authorization") String auth,
                                           @RequestBody Map<String, Object> body) {
        resolveUser(auth); // 仍要求 ADMIN
        String cookie = str(body, "cookie");
        if (cookie == null || cookie.isBlank()) return bad("cookie 不能为空");
        return ok(service.validate(cookie));
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestHeader("Authorization") String auth,
                                         @RequestBody Map<String, Object> body) {
        User u = resolveUser(auth);
        try {
            XhsCookie c = service.create(
                    u.getPrimaryOrg(),
                    str(body, "platform"),
                    str(body, "cookie"),
                    str(body, "accountLabel"),
                    str(body, "note"),
                    asInt(body, "priority"),
                    String.valueOf(u.getId()));
            return ok(c);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@RequestHeader("Authorization") String auth,
                                         @PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        User u = resolveUser(auth);
        XhsCookie.Status st = null;
        String statusStr = str(body, "status");
        if (statusStr != null) {
            try { st = XhsCookie.Status.valueOf(statusStr); }
            catch (Exception e) { return bad("status 非法: " + statusStr); }
        }
        return service.update(id, u.getPrimaryOrg(),
                str(body, "cookie"),
                str(body, "accountLabel"),
                str(body, "note"),
                asInt(body, "priority"),
                st)
                .<ResponseEntity<Object>>map(this::ok)
                .orElseGet(this::notFound);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@RequestHeader("Authorization") String auth,
                                         @PathVariable Long id) {
        User u = resolveUser(auth);
        return service.delete(id, u.getPrimaryOrg())
                ? ok(Map.of("deleted", true)) : notFound();
    }

    // ---------- 扫码登录（QR login） ----------

    /**
     * 创建一次扫码登录会话。前端随后通过 WS {@code /ws/xhs-login/{token}}
     * 订阅 {@code sessionId} 拿到：
     *   qr_ready → status → success / error
     *
     * body:
     *   platforms: ["xhs_pc","xhs_creator",...]  可选，缺省取配置里的默认列表
     */
    @PostMapping("/qr-login")
    public ResponseEntity<Object> qrLogin(@RequestHeader("Authorization") String auth,
                                          @RequestBody(required = false) Map<String, Object> body) {
        if (!loginProps.isEnabled()) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "xhs-login disabled"));
        }
        User u = resolveUser(auth);
        List<String> platforms = null;
        if (body != null && body.get("platforms") instanceof List<?> list) {
            platforms = list.stream().map(String::valueOf).toList();
        }
        try {
            XhsLoginSession s = loginService.start(u.getPrimaryOrg(), String.valueOf(u.getId()), platforms);
            return ok(Map.of(
                    "sessionId", s.getSessionId(),
                    "status", s.getStatus().name(),
                    "expiresAt", s.getExpiresAt().toString(),
                    "platforms", Arrays.asList(s.getPlatforms().split(",")),
                    "wsPathHint", "/ws/xhs-login/{token}?session=" + s.getSessionId()
            ));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @GetMapping("/qr-login/{sessionId}")
    public ResponseEntity<Object> qrLoginStatus(@RequestHeader("Authorization") String auth,
                                                @PathVariable String sessionId) {
        User u = resolveUser(auth);
        return loginService.find(sessionId)
                .filter(s -> u.getPrimaryOrg().equals(s.getOwnerOrgTag()))
                .<ResponseEntity<Object>>map(s -> ok(Map.of(
                        "sessionId", s.getSessionId(),
                        "status", s.getStatus().name(),
                        "platforms", s.getPlatforms(),
                        "capturedPlatforms", s.getCapturedPlatforms() == null ? "" : s.getCapturedPlatforms(),
                        "missingPlatforms", s.getMissingPlatforms() == null ? "" : s.getMissingPlatforms(),
                        "errorMessage", s.getErrorMessage() == null ? "" : s.getErrorMessage(),
                        "startedAt", s.getStartedAt().toString(),
                        "finishedAt", s.getFinishedAt() == null ? "" : s.getFinishedAt().toString()
                )))
                .orElseGet(this::notFound);
    }

    @PostMapping("/qr-login/{sessionId}/cancel")
    public ResponseEntity<Object> qrLoginCancel(@RequestHeader("Authorization") String auth,
                                                @PathVariable String sessionId) {
        User u = resolveUser(auth);
        return loginService.find(sessionId)
                .filter(s -> u.getPrimaryOrg().equals(s.getOwnerOrgTag()))
                .<ResponseEntity<Object>>map(s -> {
                    loginService.cancel(sessionId, "user cancelled");
                    return ok(Map.of("cancelled", true));
                })
                .orElseGet(this::notFound);
    }

    // ---------- helpers ----------

    private User resolveUser(String auth) {
        String token = auth == null ? "" : auth.replace("Bearer ", "");
        String userId = jwtUtils.extractUserIdFromToken(token);
        if (userId == null) throw new IllegalArgumentException("无效 token");
        return userResolver.resolve(userId);
    }
    private ResponseEntity<Object> ok(Object data) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", data));
    }
    private ResponseEntity<Object> notFound() {
        return ResponseEntity.status(404).body(Map.of("code", 404, "message", "not found"));
    }
    private ResponseEntity<Object> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", msg));
    }
    private String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
    private Integer asInt(Map<String, Object> m, String k) {
        Object v = m.get(k); if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (Exception e) { return null; }
    }
}
