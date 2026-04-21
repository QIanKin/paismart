package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.config.XhsLoginProperties;
import com.yizhaoqi.smartpai.model.xhs.XhsLoginSession;
import com.yizhaoqi.smartpai.repository.xhs.XhsLoginSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * XHS 扫码登录会话编排器。
 *
 * <p>职责：
 * <ol>
 *   <li>创建 / 取消会话（数据库 {@link XhsLoginSession} 的生命周期）</li>
 *   <li>拉起 {@link LoginBrowserRunner} 跑 Node 脚本</li>
 *   <li>把 runner 发过来的事件翻译成：① 更新 DB ② 广播给订阅者（WS handler）</li>
 *   <li>成功时调用 {@link XhsCookieService#bulkUpsertFromLogin} 写 cookie 池</li>
 *   <li>janitor 定时清理 EXPIRED</li>
 * </ol>
 *
 * <p>事件模型：{@link LoginEventListener} 是订阅者接口，同一 session 只允许一个活跃订阅者
 * （WebSocket 握手后注册，断开时反注册）。落到 DB 不受订阅者影响。
 */
@Service
public class XhsLoginSessionService {

    private static final Logger log = LoggerFactory.getLogger(XhsLoginSessionService.class);

    private final XhsLoginSessionRepository repo;
    private final XhsCookieService cookieService;
    private final LoginBrowserRunner runner;
    private final XhsLoginProperties props;

    /** sessionId → 订阅者列表（通常只有 WS handler）。 */
    private final ConcurrentMap<String, CopyOnWriteArrayList<LoginEventListener>> listeners = new ConcurrentHashMap<>();

    /** userId → 当前活跃 sessionId。用于 single-flight-per-user。 */
    private final ConcurrentMap<String, String> activeByUser = new ConcurrentHashMap<>();

    public XhsLoginSessionService(XhsLoginSessionRepository repo,
                                  XhsCookieService cookieService,
                                  LoginBrowserRunner runner,
                                  XhsLoginProperties props) {
        this.repo = repo;
        this.cookieService = cookieService;
        this.runner = runner;
        this.props = props;
    }

    // ---------- 对外 API ----------

    /**
     * 创建一个新会话（DB 落 PENDING），同时拉起 node 子进程开始采二维码。
     * 不会阻塞等待二维码；调用方应随后订阅事件。
     */
    @Transactional
    public XhsLoginSession start(String ownerOrgTag, String userId, List<String> platforms) {
        if (ownerOrgTag == null || ownerOrgTag.isBlank()) {
            throw new IllegalArgumentException("ownerOrgTag 必填");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 必填");
        }
        List<String> plats = (platforms == null || platforms.isEmpty())
                ? Arrays.stream(props.getDefaultPlatforms().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                : platforms.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (plats.isEmpty()) throw new IllegalArgumentException("platforms 不能为空");

        // single-flight
        if (props.isSingleFlightPerUser()) {
            String prev = activeByUser.get(userId);
            if (prev != null) {
                repo.findBySessionId(prev).ifPresent(s -> {
                    if (!s.isTerminal()) {
                        cancel(prev, "superseded by new login");
                    }
                });
            }
        }

        XhsLoginSession s = new XhsLoginSession();
        s.setSessionId(UUID.randomUUID().toString());
        s.setOwnerOrgTag(ownerOrgTag);
        s.setCreatedByUserId(userId);
        s.setPlatforms(String.join(",", plats));
        s.setStatus(XhsLoginSession.Status.PENDING);
        s.setStartedAt(LocalDateTime.now());
        s.setExpiresAt(LocalDateTime.now().plusSeconds(props.getExpiresSeconds()));
        XhsLoginSession saved = repo.save(s);
        activeByUser.put(userId, saved.getSessionId());

        LoginBrowserRunner.StartRequest req = new LoginBrowserRunner.StartRequest();
        req.sessionId = saved.getSessionId();
        req.platforms = String.join(",", plats);
        req.timeoutSeconds = props.getExpiresSeconds();

        boolean ok = runner.start(req, ev -> handleEvent(saved.getSessionId(), ev));
        if (!ok) {
            // runner.start 同步报错时已推了 error 事件，还是要把 DB 打成 FAILED
            finish(saved.getSessionId(), XhsLoginSession.Status.FAILED, "runner 启动失败", null, null);
        }
        return saved;
    }

    public Optional<XhsLoginSession> find(String sessionId) {
        return repo.findBySessionId(sessionId);
    }

    /** 供前端列表展示；默认取最近 50 条。 */
    public List<XhsLoginSession> recent(String ownerOrgTag) {
        List<XhsLoginSession> all = repo.findByOwnerOrgTagOrderByIdDesc(ownerOrgTag);
        return all.size() > 50 ? all.subList(0, 50) : all;
    }

    /** 业务员主动取消（或管理员兜底）。 */
    @Transactional
    public void cancel(String sessionId, String reason) {
        runner.cancel(sessionId);
        finish(sessionId, XhsLoginSession.Status.CANCELLED, reason, null, null);
    }

    // ---------- 订阅 ----------

    /** WS handler 订阅事件。返回的 token 用于 {@link #unsubscribe} 反注册。 */
    public void subscribe(String sessionId, LoginEventListener listener) {
        listeners.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(listener);
        // 订阅后立即把当前 DB 状态推一次，避免 WS 连上时错过 QR_READY
        repo.findBySessionId(sessionId).ifPresent(s -> {
            Map<String, Object> snapshot = snapshot(s);
            safeFire(listener, "snapshot", snapshot);
        });
    }

    public void unsubscribe(String sessionId, LoginEventListener listener) {
        List<LoginEventListener> list = listeners.get(sessionId);
        if (list != null) list.remove(listener);
    }

    // ---------- runner 事件处理 ----------

    /**
     * 每次 LoginBrowserRunner 发来事件时回调。
     * 把事件翻译成 DB 状态变更 + 广播给订阅者。
     */
    @Transactional
    public void handleEvent(String sessionId, LoginBrowserRunner.LoginEvent ev) {
        Optional<XhsLoginSession> found = repo.findBySessionId(sessionId);
        if (found.isEmpty()) {
            log.warn("handleEvent: session={} 找不到，忽略事件 type={}", sessionId, ev.type());
            return;
        }
        XhsLoginSession s = found.get();
        if (s.isTerminal()) {
            // 已到终态的后续事件（比如 closed）只做广播，不改 DB
            broadcast(sessionId, ev.type(), nodeToMap(ev.payload()));
            return;
        }

        switch (ev.type()) {
            case "qr_ready" -> {
                s.setStatus(XhsLoginSession.Status.QR_READY);
                String dataUrl = ev.payload().path("dataUrl").asText(null);
                s.setQrDataUrl(dataUrl);
                repo.save(s);
                broadcast(sessionId, "qr_ready", Map.of("dataUrl", dataUrl == null ? "" : dataUrl));
            }
            case "status" -> {
                String st = ev.payload().path("status").asText("");
                try {
                    XhsLoginSession.Status next = XhsLoginSession.Status.valueOf(st);
                    // 只允许往前推（不能回退）
                    if (next.ordinal() > s.getStatus().ordinal()) {
                        s.setStatus(next);
                        repo.save(s);
                    }
                } catch (Exception ignore) {
                    log.debug("忽略未知 status={}", st);
                }
                broadcast(sessionId, "status", Map.of("status", st));
            }
            case "success" -> {
                // 解析 cookies 映射
                JsonNode cookiesNode = ev.payload().path("cookies");
                List<XhsCookieService.PlatformCookie> items = new ArrayList<>();
                List<String> captured = new ArrayList<>();
                if (cookiesNode.isObject()) {
                    Iterator<String> it = cookiesNode.fieldNames();
                    while (it.hasNext()) {
                        String platform = it.next();
                        String cookie = cookiesNode.path(platform).asText("");
                        if (cookie != null && !cookie.isBlank()) {
                            items.add(new XhsCookieService.PlatformCookie(platform, cookie));
                            captured.add(platform);
                        }
                    }
                }
                List<String> requested = Arrays.stream(s.getPlatforms().split(","))
                        .map(String::trim).filter(x -> !x.isEmpty()).toList();
                List<String> missing = requested.stream()
                        .filter(p -> !captured.contains(p))
                        .collect(Collectors.toList());

                String label = "扫码登录 @ " + s.getStartedAt().withNano(0);
                XhsCookieService.BulkUpsertResult res = cookieService.bulkUpsertFromLogin(
                        s.getOwnerOrgTag(),
                        s.getSessionId(),
                        label,
                        s.getCreatedByUserId(),
                        items);

                finish(sessionId, XhsLoginSession.Status.SUCCESS, null,
                        String.join(",", captured),
                        missing.isEmpty() ? null : String.join(",", missing));

                broadcast(sessionId, "success", Map.of(
                        "captured", captured,
                        "missing", missing,
                        "createdIds", res.createdIds(),
                        "updatedIds", res.updatedIds(),
                        "skipped", res.skipped()
                ));
            }
            case "error" -> {
                String type = ev.payload().path("errorType").asText("unknown");
                String msg = ev.payload().path("message").asText("");
                finish(sessionId, XhsLoginSession.Status.FAILED, type + ": " + msg, null, null);
                broadcast(sessionId, "error", Map.of("errorType", type, "message", msg));
            }
            case "closed" -> {
                // 子进程退出。若此时仍非终态，意味着异常退出，按 FAILED 处理
                if (!s.isTerminal()) {
                    int exit = ev.payload().path("exitCode").asInt(-1);
                    finish(sessionId, XhsLoginSession.Status.FAILED,
                            "runner exit " + exit, null, null);
                }
                broadcast(sessionId, "closed", Map.of("exitCode", ev.payload().path("exitCode").asInt(-1)));
                // 清理订阅
                listeners.remove(sessionId);
                // 清 active 索引
                activeByUser.remove(s.getCreatedByUserId(), sessionId);
            }
            default -> {
                // 透传未知事件给前端（比如 debug 日志）
                broadcast(sessionId, ev.type(), nodeToMap(ev.payload()));
            }
        }
    }

    // ---------- janitor ----------

    /** 定时回收过期会话。cron 默认每分钟一次。 */
    @Scheduled(cron = "${smartpai.xhs-login.janitor-cron:0 * * * * *}")
    @Transactional
    public void reapExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<XhsLoginSession.Status> live = Arrays.asList(
                XhsLoginSession.Status.PENDING,
                XhsLoginSession.Status.QR_READY,
                XhsLoginSession.Status.SCANNED,
                XhsLoginSession.Status.CONFIRMED);
        List<XhsLoginSession> expired = repo.findByStatusInAndExpiresAtBefore(live, now);
        for (XhsLoginSession s : expired) {
            log.info("XhsLogin janitor: 过期回收 session={} status={}", s.getSessionId(), s.getStatus());
            runner.cancel(s.getSessionId());
            finish(s.getSessionId(), XhsLoginSession.Status.EXPIRED, "timeout", null, null);
            broadcast(s.getSessionId(), "error",
                    Map.of("errorType", "expired", "message", "登录超时（默认 " + props.getExpiresSeconds() + "s）"));
            listeners.remove(s.getSessionId());
            activeByUser.remove(s.getCreatedByUserId(), s.getSessionId());
        }
    }

    // ---------- 内部工具 ----------

    @Transactional
    protected void finish(String sessionId, XhsLoginSession.Status status, String errMsg,
                          String captured, String missing) {
        repo.findBySessionId(sessionId).ifPresent(s -> {
            if (s.isTerminal()) return;
            s.setStatus(status);
            s.setFinishedAt(LocalDateTime.now());
            if (errMsg != null) s.setErrorMessage(errMsg.substring(0, Math.min(512, errMsg.length())));
            if (captured != null) s.setCapturedPlatforms(captured);
            if (missing != null) s.setMissingPlatforms(missing);
            // 终态清掉二维码，别长期保留
            s.setQrDataUrl(null);
            repo.save(s);
        });
    }

    private void broadcast(String sessionId, String type, Map<String, Object> payload) {
        List<LoginEventListener> list = listeners.get(sessionId);
        if (list == null || list.isEmpty()) return;
        for (LoginEventListener l : list) {
            safeFire(l, type, payload);
        }
    }

    private void safeFire(LoginEventListener l, String type, Map<String, Object> payload) {
        try {
            l.onEvent(type, payload);
        } catch (Exception e) {
            log.warn("订阅者处理事件抛异常 type={} err={}", type, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeToMap(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(node, Map.class);
        } catch (Exception e) {
            return Map.of("raw", node.toString());
        }
    }

    private Map<String, Object> snapshot(XhsLoginSession s) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("sessionId", s.getSessionId());
        m.put("status", s.getStatus().name());
        m.put("platforms", s.getPlatforms());
        m.put("capturedPlatforms", s.getCapturedPlatforms());
        m.put("missingPlatforms", s.getMissingPlatforms());
        m.put("errorMessage", s.getErrorMessage());
        m.put("qrDataUrl", s.getQrDataUrl());
        m.put("startedAt", s.getStartedAt() == null ? null : s.getStartedAt().toString());
        m.put("finishedAt", s.getFinishedAt() == null ? null : s.getFinishedAt().toString());
        m.put("expiresAt", s.getExpiresAt() == null ? null : s.getExpiresAt().toString());
        return m;
    }

    /** WS handler 实现此接口。 */
    public interface LoginEventListener extends Consumer<Map<String, Object>> {
        void onEvent(String type, Map<String, Object> payload);

        @Override
        default void accept(Map<String, Object> payload) {
            onEvent((String) payload.getOrDefault("type", ""), payload);
        }
    }
}
