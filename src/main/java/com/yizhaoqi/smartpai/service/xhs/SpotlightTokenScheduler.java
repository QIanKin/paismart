package com.yizhaoqi.smartpai.service.xhs;

import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.repository.xhs.XhsCookieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 聚光 MarketingAPI OAuth2 access_token 自动续签调度器。
 *
 * <p>为什么需要：聚光 access_token 官方 2 小时过期，{@code refresh_token} 7 天过期。
 * 没有自动续签就得每 2 小时人肉登聚光开放平台刷一次 —— 交付后无人值守会直接让所有
 * {@code spotlight_*} 工具挂掉。本类每 10 分钟扫一次，对即将在 {@code thresholdMinutes}
 * 分钟内过期的凭证调 {@link SpotlightTokenRefresher#refresh} 续签，把 access_token /
 * refresh_token / expiresAt 原地更新回 {@code xhs_cookies} 行。
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>{@code smartpai.xhs.spotlight.app-id} 或 {@code app-secret} 任一为空 → 每次执行只打 WARN，
 *       不抛异常、不重试。方便客户首次部署时"先跑空架子再慢慢接"。</li>
 *   <li>刷新失败（refresh_token 被用过/过期/远端错） → 打 ERROR，把 cookie 状态置为 EXPIRED，
 *       让前端 /data-sources 明显飘红，运营人工介入。</li>
 * </ul>
 */
@Component
public class SpotlightTokenScheduler {

    private static final Logger log = LoggerFactory.getLogger(SpotlightTokenScheduler.class);

    private final SpotlightTokenRefresher refresher;
    private final XhsCookieRepository cookieRepo;

    @Value("${smartpai.xhs.spotlight.app-id:}")
    private String appId;

    @Value("${smartpai.xhs.spotlight.app-secret:}")
    private String appSecret;

    /** access_token 距离过期不足多少分钟时触发续签。默认 30 分钟，留够 3 次重试余量。 */
    @Value("${smartpai.xhs.spotlight.refresh-threshold-minutes:30}")
    private long thresholdMinutes;

    public SpotlightTokenScheduler(SpotlightTokenRefresher refresher,
                                   XhsCookieRepository cookieRepo) {
        this.refresher = refresher;
        this.cookieRepo = cookieRepo;
    }

    /** 每 10 分钟跑一次；启动 1 分钟后执行第一次，避开冷启动。 */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 600_000L)
    public void tick() {
        if (isBlank(appId) || isBlank(appSecret)) {
            // 首次部署常态：客户还没去聚光开放平台拿 app_id/app_secret。打 warn 让他看日志就能定位。
            log.warn("[SpotlightTokenScheduler] 未配置 XHS_SPOTLIGHT_APP_ID / XHS_SPOTLIGHT_APP_SECRET，"
                    + "access_token 无法自动续签。请到聚光开放平台申请应用后填到 .env 并重启。");
            return;
        }

        List<XhsCookie> rows = cookieRepo.findAll().stream()
                .filter(c -> "xhs_spotlight".equalsIgnoreCase(c.getPlatform()))
                .filter(c -> c.getStatus() == XhsCookie.Status.ACTIVE)
                .toList();

        if (rows.isEmpty()) {
            return;
        }

        int refreshed = 0;
        int skipped = 0;

        for (XhsCookie c : rows) {
            // 简化策略：不解密看 expiresAt，直接按 updatedAt 做"还剩 < 30min 就刷"的近似判断，
            // 避免给 scheduler 多挂一个 cipher 依赖。聚光 access_token TTL = 2h，只要 updatedAt
            // 距现在 >= 90min 就进入刷新窗口。
            long ageMin = c.getUpdatedAt() == null
                    ? Long.MAX_VALUE
                    : java.time.Duration.between(c.getUpdatedAt(), java.time.LocalDateTime.now()).toMinutes();
            long refreshAtMin = 120 - thresholdMinutes; // 默认 90min
            if (ageMin < refreshAtMin) {
                skipped++;
                continue;
            }

            try {
                SpotlightTokenRefresher.Result r = refresher.refresh(c.getId(), c.getOwnerOrgTag());
                if (r.ok()) {
                    refreshed++;
                    log.info("[SpotlightTokenScheduler] 续签成功 cookieId={} org={} label={}",
                            c.getId(), c.getOwnerOrgTag(), c.getAccountLabel());
                } else {
                    log.error("[SpotlightTokenScheduler] 续签失败 cookieId={} errorType={} message={}",
                            c.getId(), r.errorType(), r.message());
                }
            } catch (Exception e) {
                log.error("[SpotlightTokenScheduler] 续签异常 cookieId={} err={}", c.getId(), e.toString());
            }
        }

        if (refreshed > 0 || skipped == 0) {
            log.info("[SpotlightTokenScheduler] 巡检完成 total={} refreshed={} skipped={}",
                    rows.size(), refreshed, skipped);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
