package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.repository.creator.CreatorRepository;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.xhs.XhsRefreshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时刷新「活跃项目名册」里的博主最近笔记。<br>
 * <p>
 * 与「手动点击刷新」和「Agent 触发刷新」共享同一 {@link XhsRefreshService}。<br>
 * 这里只给 xhs 平台账号跑，其他平台没有对应 skill 就跳过。
 * <p>
 * 节流策略：
 *  - 每账号最多每 {@code skipIfSnapshotWithinHours} 小时刷一次，避免被同类手动操作叠加；
 *  - 每次最多处理 {@code maxPerRun} 个账号，溢出的下一轮再来；
 *  - 失败的只记日志，不阻塞下一个。
 */
@Component
public class ProjectCreatorRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProjectCreatorRefreshScheduler.class);

    private final ProjectCreatorService rosterService;
    private final CreatorRepository creatorRepository;
    private final CreatorService creatorService;
    private final XhsRefreshService xhsRefreshService;

    @Value("${smartpai.creator-refresh.enabled:true}")
    private boolean enabled;

    @Value("${smartpai.creator-refresh.skip-if-snapshot-within-hours:12}")
    private int skipIfSnapshotWithinHours;

    @Value("${smartpai.creator-refresh.max-per-run:20}")
    private int maxPerRun;

    @Value("${smartpai.creator-refresh.per-account-limit:20}")
    private int perAccountLimit;

    public ProjectCreatorRefreshScheduler(ProjectCreatorService rosterService,
                                          CreatorRepository creatorRepository,
                                          CreatorService creatorService,
                                          XhsRefreshService xhsRefreshService) {
        this.rosterService = rosterService;
        this.creatorRepository = creatorRepository;
        this.creatorService = creatorService;
        this.xhsRefreshService = xhsRefreshService;
    }

    /**
     * 默认每天凌晨 03:10 跑一次；可通过配置 {@code smartpai.creator-refresh.cron} 覆盖。
     * 这里对应企业内部场景，半夜跑不影响用户。
     */
    @Scheduled(cron = "${smartpai.creator-refresh.cron:0 10 3 * * *}")
    public void refreshActiveRosters() {
        if (!enabled) {
            log.debug("ProjectCreatorRefreshScheduler disabled，跳过");
            return;
        }
        // 收集"哪些租户 × 哪些博主"需要刷：遍历所有租户的 creator 表
        // 规模通常可控，直接按 orgTag 分组处理；若未来规模增大再换成 SQL 聚合。
        List<String> orgTags = creatorRepository.findDistinctOwnerOrgTag();
        int total = 0;
        for (String orgTag : orgTags) {
            if (total >= maxPerRun) break;
            try {
                total += refreshForOrg(orgTag, maxPerRun - total);
            } catch (Exception e) {
                log.warn("refreshForOrg 失败 orgTag={} err={}", orgTag, e.getMessage(), e);
            }
        }
        log.info("ProjectCreatorRefreshScheduler 本轮刷新账号 {} 个", total);
    }

    /** @return 本 org 本次实际触发刷新的账号数 */
    private int refreshForOrg(String orgTag, int budget) {
        List<Long> creatorIds = rosterService.findActiveCreatorIdsForOrg(orgTag);
        if (creatorIds.isEmpty()) return 0;
        int touched = 0;
        LocalDateTime skipBefore = LocalDateTime.now().minusHours(Math.max(1, skipIfSnapshotWithinHours));
        for (Long cid : creatorIds) {
            if (touched >= budget) break;
            Creator creator = creatorRepository.findById(cid).orElse(null);
            if (creator == null || !orgTag.equals(creator.getOwnerOrgTag())) continue;
            List<CreatorAccount> accounts = creatorService.getAccountsByCreator(cid, orgTag);
            for (CreatorAccount a : accounts) {
                if (touched >= budget) break;
                // 目前只刷 xhs 账号；其他平台等对应 skill 接入后再来
                if (!"xhs".equalsIgnoreCase(a.getPlatform())) continue;
                LocalDateTime last = a.getLatestSnapshotAt();
                if (last != null && last.isAfter(skipBefore)) continue;
                try {
                    XhsRefreshService.Result r = xhsRefreshService.refreshAccount(
                            a.getId(), orgTag, perAccountLimit, false,
                            "scheduler-" + orgTag + "-" + a.getId(), null);
                    if (r.ok()) {
                        touched++;
                        log.debug("scheduler refreshed account {} (+{} new, {} updated)",
                                a.getId(), r.inserted(), r.updated());
                    } else {
                        log.warn("scheduler 刷新 account {} 失败 type={} msg={}",
                                a.getId(), r.errorType(), r.errorMessage());
                    }
                } catch (Exception e) {
                    log.warn("scheduler 刷新 account {} 异常: {}", a.getId(), e.getMessage());
                }
            }
        }
        return touched;
    }
}
