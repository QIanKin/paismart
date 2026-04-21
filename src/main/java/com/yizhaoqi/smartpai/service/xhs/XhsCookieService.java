package com.yizhaoqi.smartpai.service.xhs;

import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.repository.xhs.XhsCookieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * XhsCookie 的增删改查 + 轮转选择。
 *
 * 挑选策略 {@link #pickAvailable}：
 *  1. 过滤 ACTIVE + 相同 org + 相同 platform；
 *  2. 先按 priority desc 排，priority 相同看 lastUsedAt asc（越久没用优先），再看 successCount desc
 *  3. 调用方执行完后必须调 {@link #reportSuccess} 或 {@link #reportFailure} 更新指标
 *
 * 加密：{@link CookieCipher#encrypt}。前端看不到明文，后端脚本运行时由
 * {@link #decryptFor} 解密塞进子进程环境变量 COOKIES。
 */
@Service
public class XhsCookieService {

    private static final Logger log = LoggerFactory.getLogger(XhsCookieService.class);

    private final XhsCookieRepository repo;
    private final CookieCipher cipher;

    public XhsCookieService(XhsCookieRepository repo, CookieCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    public List<XhsCookie> list(String ownerOrgTag) {
        return repo.findByOwnerOrgTagOrderByIdDesc(ownerOrgTag);
    }

    public Optional<XhsCookie> findById(Long id, String ownerOrgTag) {
        return repo.findById(id).filter(c -> ownerOrgTag.equals(c.getOwnerOrgTag()));
    }

    @Transactional
    public XhsCookie create(String ownerOrgTag, String platform, String plainCookie,
                            String accountLabel, String note, Integer priority, String createdBy) {
        if (ownerOrgTag == null || ownerOrgTag.isBlank()) throw new IllegalArgumentException("ownerOrgTag 必填");
        if (platform == null || platform.isBlank()) throw new IllegalArgumentException("platform 必填");
        if (plainCookie == null || plainCookie.isBlank()) throw new IllegalArgumentException("cookie 必填");

        Set<String> keys = extractCookieKeys(plainCookie);
        // 硬校验：Spider_XHS 签名生成器 get_request_headers_params 必须要 a1，缺就运行时 KeyError；
        // web_session/webId 缺的话绝大多数用户态接口会 401/406，所以一起要求。
        // 对非 xhs-web-cookie 平台（spotlight OAuth、competitor 站点 API 等）跳过该校验。
        if (isXhsWebCookiePlatform(platform)) {
            List<String> missing = missingRequired(keys);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                        "Cookie 缺少必要字段: " + String.join("/", missing)
                                + "。请从浏览器 DevTools → Application → Cookies → https://www.xiaohongshu.com "
                                + "把 a1 / web_session / webId 一起复制过来，格式示例：a1=xxx; web_session=xxx; webId=xxx");
            }
        }

        XhsCookie c = new XhsCookie();
        c.setOwnerOrgTag(ownerOrgTag);
        c.setPlatform(platform.trim());
        c.setAccountLabel(accountLabel);
        c.setNote(note);
        c.setPriority(priority == null ? 10 : priority);
        c.setStatus(XhsCookie.Status.ACTIVE);
        c.setSuccessCount(0);
        c.setFailCount(0);
        c.setCookieEncrypted(cipher.encrypt(plainCookie));
        c.setCookiePreview(cipher.preview(plainCookie));
        c.setCookieKeys(String.join(",", keys));
        c.setCreatedBy(createdBy);
        return repo.save(c);
    }

    @Transactional
    public Optional<XhsCookie> update(Long id, String ownerOrgTag, String plainCookie,
                                      String accountLabel, String note,
                                      Integer priority, XhsCookie.Status status) {
        return findById(id, ownerOrgTag).map(c -> {
            if (plainCookie != null && !plainCookie.isBlank()) {
                Set<String> keys = extractCookieKeys(plainCookie);
                if (isXhsWebCookiePlatform(c.getPlatform())) {
                    List<String> missing = missingRequired(keys);
                    if (!missing.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Cookie 缺少必要字段: " + String.join("/", missing)
                                        + "。至少需要 a1 / web_session / webId 三个，请重新从浏览器整串拷贝。");
                    }
                }
                c.setCookieEncrypted(cipher.encrypt(plainCookie));
                c.setCookiePreview(cipher.preview(plainCookie));
                c.setCookieKeys(String.join(",", keys));
                c.setFailCount(0);
                c.setLastError(null);
            }
            if (accountLabel != null) c.setAccountLabel(accountLabel);
            if (note != null) c.setNote(note);
            if (priority != null) c.setPriority(priority);
            if (status != null) c.setStatus(status);
            return repo.save(c);
        });
    }

    // ---------- cookie 字符串解析 / 必填校验 ----------

    /** Spider_XHS 实战所需最小集合：a1 签名必需；web_session/webId 用户态风控必需。 */
    public static final List<String> REQUIRED_COOKIE_FIELDS = List.of("a1", "web_session", "webId");

    /** "a1=xxx; web_session=yyy;webId=zzz" → {"a1","web_session","webId"} */
    public static Set<String> extractCookieKeys(String plainCookie) {
        Set<String> keys = new LinkedHashSet<>();
        if (plainCookie == null) return keys;
        String[] segs = plainCookie.contains("; ") ? plainCookie.split("; ") : plainCookie.split(";");
        for (String s : segs) {
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String k = s.substring(0, eq).trim();
            if (!k.isEmpty()) keys.add(k);
        }
        return keys;
    }

    /** 找出 REQUIRED_COOKIE_FIELDS 里没出现的那些，顺序保留原顺序。 */
    public static List<String> missingRequired(Set<String> presentKeys) {
        List<String> missing = new ArrayList<>();
        for (String f : REQUIRED_COOKIE_FIELDS) {
            if (!presentKeys.contains(f)) missing.add(f);
        }
        return missing;
    }

    /** 给 controller 层复用：校验字符串但不写库，用于前端 "先贴再看完不完整" 的预览。 */
    public ValidationReport validate(String plainCookie) {
        Set<String> keys = extractCookieKeys(plainCookie);
        List<String> missing = missingRequired(keys);
        return new ValidationReport(new ArrayList<>(keys), missing, missing.isEmpty());
    }

    public record ValidationReport(List<String> detectedKeys, List<String> missingRequired, boolean ok) {}

    @Transactional
    public boolean delete(Long id, String ownerOrgTag) {
        return findById(id, ownerOrgTag).map(c -> {
            repo.delete(c);
            return true;
        }).orElse(false);
    }

    // ---------- 运行时：挑选 + 反馈 ----------

    /**
     * 公司级共享 cookie 池所在的 org：admin 在 default org 录的 cookie，
     * 所有员工（不论自己 org）都可以"兜底"使用。对 MCN 场景是最符合直觉的策略：
     * cookie 是公司资产，由运维统一管理。
     *
     * 本 org 找不到可用 cookie 时回退到 {@link #SHARED_ORG_TAG}。
     */
    public static final String SHARED_ORG_TAG = "default";

    @Transactional
    public Optional<Picked> pickAvailable(String ownerOrgTag, String platform) {
        Optional<Picked> hit = pickWithin(ownerOrgTag, platform);
        if (hit.isPresent()) return hit;
        // 本 org 没有时，回退到公司共享池
        if (!SHARED_ORG_TAG.equalsIgnoreCase(ownerOrgTag)) {
            Optional<Picked> shared = pickWithin(SHARED_ORG_TAG, platform);
            if (shared.isPresent()) {
                log.debug("XhsCookie fallback: org={} 无 {} 可用，使用共享 org={} 的 cookie #{}",
                        ownerOrgTag, platform, SHARED_ORG_TAG, shared.get().cookieId());
            }
            return shared;
        }
        return Optional.empty();
    }

    private Optional<Picked> pickWithin(String ownerOrgTag, String platform) {
        List<XhsCookie> candidates = repo.findByOwnerOrgTagAndPlatformAndStatus(
                ownerOrgTag, platform, XhsCookie.Status.ACTIVE);
        if (candidates.isEmpty()) return Optional.empty();
        candidates.sort(Comparator
                .comparingInt((XhsCookie c) -> c.getPriority() == null ? 0 : c.getPriority()).reversed()
                .thenComparing(Comparator.comparing(
                        XhsCookie::getLastUsedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .thenComparingInt((XhsCookie c) -> c.getSuccessCount() == null ? 0 : c.getSuccessCount()).reversed());
        XhsCookie pick = candidates.get(0);
        pick.setLastUsedAt(LocalDateTime.now());
        repo.save(pick);
        String plain = cipher.decrypt(pick.getCookieEncrypted());
        return Optional.of(new Picked(pick.getId(), plain));
    }

    public Optional<String> decryptFor(Long id, String ownerOrgTag) {
        return findById(id, ownerOrgTag).map(c -> cipher.decrypt(c.getCookieEncrypted()));
    }

    @Transactional
    public void reportSuccess(Long id) {
        repo.findById(id).ifPresent(c -> {
            c.setSuccessCount((c.getSuccessCount() == null ? 0 : c.getSuccessCount()) + 1);
            c.setLastCheckedAt(LocalDateTime.now());
            c.setLastError(null);
            if (c.getPriority() == null) c.setPriority(10);
            // 成功后+1，但设上限避免无限增长
            if (c.getPriority() < 100) c.setPriority(c.getPriority() + 1);
            repo.save(c);
        });
    }

    @Transactional
    public void reportFailure(Long id, String error) {
        repo.findById(id).ifPresent(c -> {
            int fails = (c.getFailCount() == null ? 0 : c.getFailCount()) + 1;
            c.setFailCount(fails);
            c.setLastCheckedAt(LocalDateTime.now());
            c.setLastError(error == null ? "unknown" : error.substring(0, Math.min(255, error.length())));
            int pri = c.getPriority() == null ? 10 : c.getPriority();
            c.setPriority(Math.max(0, pri - 2));
            if (fails >= 5) {
                c.setStatus(XhsCookie.Status.EXPIRED);
                log.warn("XhsCookie #{} 连续失败 {} 次，自动标记 EXPIRED", c.getId(), fails);
            }
            repo.save(c);
        });
    }

    /**
     * 立即把 cookie 标记为不可用状态（对应反爬明确信号：验证码/滑块/账号异常/凭证失效）。
     * 与 {@link #reportFailure} 不同：不等连续失败次数，一次命中就废。
     *
     * @param id       cookie id
     * @param reason   可读原因（如 "xhs signal: 验证码"），会截断到 255 字符落到 lastError
     * @param status   目标状态（通常 EXPIRED 或 BANNED）
     */
    @Transactional
    public void markDead(Long id, String reason, XhsCookie.Status status) {
        if (id == null || status == null) return;
        repo.findById(id).ifPresent(c -> {
            c.setStatus(status);
            c.setPriority(0);
            c.setLastCheckedAt(LocalDateTime.now());
            if (reason != null) {
                c.setLastError(reason.substring(0, Math.min(255, reason.length())));
            }
            repo.save(c);
            log.warn("XhsCookie #{} 明确失效：status={} reason={}", c.getId(), status, reason);
        });
    }

    public record Picked(Long cookieId, String cookie) {}

    // ---------- 扫码登录批量导入 ----------

    /**
     * 从一次扫码登录采到的多平台 cookie 批量 upsert 到 cookie 池。
     *
     * <p>同一 {@code ownerOrgTag + platform + accountLabel} 视为同一条，存在则覆盖（旋转），
     * 不存在则新增。新增 / 覆盖都会：
     * <ul>
     *     <li>重算 cookiePreview / cookieKeys</li>
     *     <li>把 status 置回 ACTIVE，priority 置回基础值 10，failCount 清零</li>
     *     <li>source 设为 QR_LOGIN，loginSessionId 写入来源会话 id，方便审计</li>
     * </ul>
     *
     * <p>accountLabel 用规则 {@code "扫码登录 @ yyyy-MM-dd HH:mm"}，
     * 同一会话里 4 个平台共享同一 label，下次再扫就覆盖上次这批。
     */
    @Transactional
    public BulkUpsertResult bulkUpsertFromLogin(String ownerOrgTag,
                                                String loginSessionId,
                                                String accountLabel,
                                                String createdBy,
                                                List<PlatformCookie> items) {
        if (ownerOrgTag == null || ownerOrgTag.isBlank()) {
            throw new IllegalArgumentException("ownerOrgTag 必填");
        }
        if (items == null || items.isEmpty()) {
            return new BulkUpsertResult(List.of(), List.of(), List.of());
        }
        List<Long> created = new ArrayList<>();
        List<Long> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        String label = (accountLabel == null || accountLabel.isBlank())
                ? "扫码登录 @ " + LocalDateTime.now().withNano(0)
                : accountLabel;

        for (PlatformCookie item : items) {
            String platform = item.platform();
            String plain = item.cookie();
            if (platform == null || platform.isBlank() || plain == null || plain.isBlank()) {
                skipped.add(platform + ":empty");
                continue;
            }
            Set<String> keys = extractCookieKeys(plain);
            if (isXhsWebCookiePlatform(platform)) {
                List<String> missing = missingRequired(keys);
                if (!missing.isEmpty()) {
                    log.warn("bulkUpsertFromLogin: platform={} 缺失必要字段={}，跳过", platform, missing);
                    skipped.add(platform + ":missing=" + String.join("/", missing));
                    continue;
                }
            }
            Optional<XhsCookie> existing = repo.findFirstByOwnerOrgTagAndPlatformAndAccountLabel(
                    ownerOrgTag, platform, label);

            XhsCookie c = existing.orElseGet(XhsCookie::new);
            boolean isNew = existing.isEmpty();
            if (isNew) {
                c.setOwnerOrgTag(ownerOrgTag);
                c.setPlatform(platform);
                c.setAccountLabel(label);
                c.setCreatedBy(createdBy);
                c.setSuccessCount(0);
            }
            c.setCookieEncrypted(cipher.encrypt(plain));
            c.setCookiePreview(cipher.preview(plain));
            c.setCookieKeys(String.join(",", keys));
            c.setStatus(XhsCookie.Status.ACTIVE);
            c.setPriority(10);
            c.setFailCount(0);
            c.setLastError(null);
            c.setSource(XhsCookie.Source.QR_LOGIN);
            c.setLoginSessionId(loginSessionId);
            c.setLastCheckedAt(LocalDateTime.now());
            XhsCookie saved = repo.save(c);
            if (isNew) created.add(saved.getId()); else updated.add(saved.getId());
        }
        log.info("bulkUpsertFromLogin org={} session={} created={} updated={} skipped={}",
                ownerOrgTag, loginSessionId, created.size(), updated.size(), skipped);
        return new BulkUpsertResult(created, updated, skipped);
    }

    /** 扫码登录返回的单平台 cookie 字符串。 */
    public record PlatformCookie(String platform, String cookie) {}

    /** bulk upsert 结果：新建 / 更新 / 因字段缺失或空而跳过。 */
    public record BulkUpsertResult(List<Long> createdIds, List<Long> updatedIds, List<String> skipped) {}

    // ---------- 常量 ----------
    /**
     * 所有数据源平台常量：
     *  - xhs_pc / xhs_creator / xhs_pgy / xhs_qianfan：基于浏览器 Cookie 的 Spider_XHS 抓取通道（a1/web_session/webId 必填）
     *  - xhs_spotlight：小红书聚光 MarketingAPI，OAuth2 access_token（凭证以 JSON 或 k=v 形式存放）
     *  - xhs_competitor：xhsCompetitorNote_website 接入点（Supabase URL + service_role key）
     */
    public static final List<String> PLATFORMS = Collections.unmodifiableList(List.of(
            "xhs_pc", "xhs_creator", "xhs_pgy", "xhs_qianfan",
            "xhs_spotlight", "xhs_competitor"
    ));

    /** 哪些 platform 值走 Spider_XHS 风格的 cookie 串（需要 a1/web_session/webId）。 */
    public static boolean isXhsWebCookiePlatform(String platform) {
        if (platform == null) return false;
        String p = platform.trim().toLowerCase();
        return p.equals("xhs_pc") || p.equals("xhs_creator") || p.equals("xhs_pgy") || p.equals("xhs_qianfan");
    }
}
