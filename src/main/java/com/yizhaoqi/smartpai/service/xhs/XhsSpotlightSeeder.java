package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.repository.xhs.XhsCookieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 启动时把环境变量里的 xhs_spotlight（聚光 MarketingAPI）OAuth2 凭证幂等写入 {@code xhs_cookies}。
 *
 * <p>动机：每次新环境上线都要"admin 登进去 → 打开 /data-sources → 粘三个 token → 保存"一轮非常烦。
 * 现在把 advertiser_id / access_token / refresh_token 挂成环境变量，启动一次自动落库，
 * 重启不会重复写（按 org+platform+accountLabel 幂等）。之后轮换只需要改 env + 删旧记录即可。
 *
 * <h3>触发条件（全部 true 才执行）</h3>
 * <ol>
 *   <li>{@code smartpai.xhs.spotlight-seed.enabled=true}</li>
 *   <li>advertiser-id / access-token / refresh-token 三者都非空</li>
 *   <li>同 {@code org+platform=xhs_spotlight+accountLabel} 在库里还没有</li>
 * </ol>
 *
 * <h3>与 {@link XhsCookieHealthService#probeSpotlight} 的契约</h3>
 * probeSpotlight 通过 JSON 的 {@code expiresAt} 判断是否过期，所以这里一定要写 expiresAt。
 * 如果 env 没传 expires-at，就按聚光 access_token 官方 "2h 过期" 的规则写 now + 2h。
 */
@Component
@Order(200) // 跑在主 XhsCookieSeeder(@Order 未指定 ≈ LOWEST_PRECEDENCE) 之前都行，彼此不依赖
public class XhsSpotlightSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(XhsSpotlightSeeder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final XhsCookieService service;
    private final XhsCookieRepository repo;

    @Value("${smartpai.xhs.spotlight-seed.enabled:false}")
    private boolean enabled;

    @Value("${smartpai.xhs.spotlight-seed.org:default}")
    private String seedOrg;

    @Value("${smartpai.xhs.spotlight-seed.label:seed-env-spotlight}")
    private String seedLabel;

    @Value("${smartpai.xhs.spotlight-seed.priority:20}")
    private int seedPriority;

    @Value("${smartpai.xhs.spotlight-seed.note:从环境变量注入的聚光 OAuth 凭证（公司共享池）}")
    private String seedNote;

    @Value("${smartpai.xhs.spotlight-seed.advertiser-id:}")
    private String advertiserId;

    @Value("${smartpai.xhs.spotlight-seed.access-token:}")
    private String accessToken;

    @Value("${smartpai.xhs.spotlight-seed.refresh-token:}")
    private String refreshToken;

    /**
     * 可选：显式指定 expiresAt（ISO-8601 带时区），为空则用 now + 2h。
     * 如果业务员知道实际拿到 token 的时间，填上能让 ping 判过期更准。
     */
    @Value("${smartpai.xhs.spotlight-seed.expires-at:}")
    private String expiresAtOverride;

    /** access_token 默认有效期（秒）。聚光官方 OAuth2 默认就是 7200s。 */
    @Value("${smartpai.xhs.spotlight-seed.default-ttl-seconds:7200}")
    private long defaultTtlSeconds;

    public XhsSpotlightSeeder(XhsCookieService service, XhsCookieRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("[XhsSpotlightSeeder] disabled, skip");
            return;
        }
        if (isBlank(advertiserId) || isBlank(accessToken) || isBlank(refreshToken)) {
            log.warn("[XhsSpotlightSeeder] 启用但缺字段：advertiserId/accessToken/refreshToken 不能为空，跳过");
            return;
        }
        try {
            Optional<XhsCookie> existing = repo.findFirstByOwnerOrgTagAndPlatformAndAccountLabel(
                    seedOrg, "xhs_spotlight", seedLabel);
            if (existing.isPresent()) {
                log.info("[XhsSpotlightSeeder] 跳过：org={} label={} 已存在 (id={})，"
                                + "若要强制刷新请先在 /data-sources 删除再重启",
                        seedOrg, seedLabel, existing.get().getId());
                return;
            }

            String cookieJson = buildCredentialJson();
            XhsCookie saved = service.create(
                    seedOrg, "xhs_spotlight", cookieJson, seedLabel, seedNote, seedPriority, "env-seeder");

            // 把 source 从默认的 MANUAL 改为 SEED，便于审计
            repo.findById(saved.getId()).ifPresent(c -> {
                c.setSource(XhsCookie.Source.SEED);
                repo.save(c);
            });

            log.info("[XhsSpotlightSeeder] 已导入聚光凭证 id={} org={} label={} advertiserId={} expiresAt≈{}",
                    saved.getId(), seedOrg, seedLabel, maskId(advertiserId), resolveExpiresAt());
        } catch (Exception e) {
            log.error("[XhsSpotlightSeeder] 导入失败", e);
        }
    }

    private String buildCredentialJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("advertiserId", advertiserId.trim());
        node.put("accessToken", accessToken.trim());
        node.put("refreshToken", refreshToken.trim());
        node.put("expiresAt", resolveExpiresAt());
        return node.toString();
    }

    private String resolveExpiresAt() {
        if (!isBlank(expiresAtOverride)) {
            // 用户自己给的，直接信任（格式错了 health ping 自然会报 cookie_invalid）
            return expiresAtOverride.trim();
        }
        return OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(defaultTtlSeconds).format(ISO);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 广告主 ID 可能敏感，日志里只留头尾各 4 位。 */
    private static String maskId(String id) {
        if (id == null || id.length() <= 10) return "****";
        return id.substring(0, 4) + "..." + id.substring(id.length() - 4);
    }
}
