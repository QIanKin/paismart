package com.yizhaoqi.smartpai.service.skill;

import com.yizhaoqi.smartpai.model.agent.MemoryItem;
import com.yizhaoqi.smartpai.model.agent.ScheduledSkillTask;
import com.yizhaoqi.smartpai.repository.agent.MemoryItemRepository;
import com.yizhaoqi.smartpai.repository.agent.ScheduledSkillTaskRepository;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 定时 Skill 任务执行器。
 *
 * 实现选择：我们用一个"每分钟扫一次 DB"的轮询策略，而不是动态 schedule 每条 cron。
 *  - 好处：新增/禁用任务即时生效；重启无 drift；支持多实例部署的乐观推进（用 next_run_at 做乐观锁）；
 *  - 代价：最细粒度为分钟级。对于 MCN 场景（刷新爆款、赛道、博主快照）完全够用。
 *
 * 成功执行后：
 *  - stdout 写成 MemoryItem（source=fact），title = taskName + yyyy-MM-dd；
 *  - 失败：记录 lastError，不写 memory；
 *  - output_mode=none：不记忆，只留 run log。
 */
@Component
public class ScheduledSkillRunner {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledSkillRunner.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final ScheduledSkillTaskRepository taskRepository;
    private final MemoryItemRepository memoryItemRepository;
    private final SkillRegistry skillRegistry;
    private final BashExecutor bashExecutor;
    private final UsageQuotaService usageQuotaService;
    private final TaskScheduler taskScheduler;

    public ScheduledSkillRunner(ScheduledSkillTaskRepository taskRepository,
                                MemoryItemRepository memoryItemRepository,
                                SkillRegistry skillRegistry,
                                BashExecutor bashExecutor,
                                UsageQuotaService usageQuotaService,
                                TaskScheduler taskScheduler) {
        this.taskRepository = taskRepository;
        this.memoryItemRepository = memoryItemRepository;
        this.skillRegistry = skillRegistry;
        this.bashExecutor = bashExecutor;
        this.usageQuotaService = usageQuotaService;
        this.taskScheduler = taskScheduler;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 15_000L)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduledSkillTask> tasks = taskRepository.findByEnabledTrue();
        for (ScheduledSkillTask t : tasks) {
            try {
                if (t.getNextRunAt() == null) {
                    t.setNextRunAt(computeNext(t.getCron(), now));
                    taskRepository.save(t);
                    continue;
                }
                if (t.getNextRunAt().isAfter(now)) continue;

                LocalDateTime next = computeNext(t.getCron(), now);
                t.setNextRunAt(next);
                t.setLastStatus(ScheduledSkillTask.LastStatus.running);
                t.setLastRunAt(now);
                taskRepository.save(t);

                taskScheduler.schedule(() -> executeTask(t.getId()), Instant.now());
            } catch (Exception e) {
                logger.warn("计划任务调度失败 id={} name={} err={}", t.getId(), t.getName(), e.getMessage());
            }
        }
    }

    void executeTask(Long taskId) {
        ScheduledSkillTask t = taskRepository.findById(taskId).orElse(null);
        if (t == null) return;
        try {
            LoadedSkill skill = skillRegistry.find(t.getSkillName(), t.getOrgTag()).orElse(null);
            if (skill == null) {
                markFailed(t, "skill 未找到/未启用: " + t.getSkillName());
                return;
            }
            if (t.getEntrypoint() == null || t.getEntrypoint().isBlank()) {
                markFailed(t, "entrypoint 未配置");
                return;
            }
            if (!skill.scriptsOrEmpty().contains(t.getEntrypoint())) {
                markFailed(t, "entrypoint 不在 skill scripts 中: " + t.getEntrypoint());
                return;
            }

            String args = t.getParamsJson() == null ? "" : "";
            String cmd = "sh ./scripts/" + t.getEntrypoint() + (args.isEmpty() ? "" : " " + args);
            BashExecutor.Request req = BashExecutor.Request.of(cmd, "scheduled-" + t.getId());
            req.extraPathEntries = List.of(skill.rootPath() + File.separator + "scripts");
            // 工作目录改为 skill 根目录，脚本用相对路径就能命中
            BashExecutor.Result r = bashExecutor.run(withWorkDir(req, skill.rootPath()));

            if (!r.success()) {
                markFailed(t, "exit=" + r.exitCode() + " " + (r.stderr() == null ? "" : trimShort(r.stderr())));
                return;
            }

            String mode = t.getOutputMode() == null ? "summary" : t.getOutputMode();
            if (!"none".equalsIgnoreCase(mode)) {
                String body = r.stdout() == null ? "" : r.stdout().trim();
                if (!body.isEmpty()) {
                    MemoryItem m = new MemoryItem();
                    m.setUserId(-1L);
                    m.setOrgTag(t.getOrgTag());
                    m.setProjectId(t.getProjectId());
                    m.setSource(MemoryItem.Source.fact);
                    m.setTitle(buildTitle(t));
                    m.setFullText(body.length() > 20_000 ? body.substring(0, 20_000) + "\n...(truncated)" : body);
                    m.setTokenEstimate(usageQuotaService.estimateTextTokens(body));
                    m.setExpiresAt(LocalDateTime.now().plusDays(7));
                    memoryItemRepository.save(m);
                }
            }

            t.setLastStatus(ScheduledSkillTask.LastStatus.success);
            t.setLastError(null);
            taskRepository.save(t);
            logger.info("ScheduledSkill 执行成功 task={} skill={} entry={} outputBytes={}",
                    t.getName(), t.getSkillName(), t.getEntrypoint(),
                    r.stdout() == null ? 0 : r.stdout().length());
        } catch (Exception e) {
            markFailed(t, e.getMessage());
        }
    }

    private BashExecutor.Request withWorkDir(BashExecutor.Request req, String workDir) {
        // BashExecutor 的 workDir 由 subDir 决定；这里用脚本相对路径让命令在 skill 根运行
        // 但 BashExecutor 目前固定以 sandboxRoot+subDir 为 cwd，为了保留 skill 根的 relative scripts/，
        // 我们传绝对路径：cmd 里直接写 sh <absolute>/scripts/xxx.sh
        if (workDir != null && req.command != null && req.command.startsWith("sh ./scripts/")) {
            req.command = "sh " + workDir + File.separator + "scripts" + File.separator
                    + req.command.substring("sh ./scripts/".length());
        }
        return req;
    }

    private void markFailed(ScheduledSkillTask t, String err) {
        t.setLastStatus(ScheduledSkillTask.LastStatus.failed);
        t.setLastError(trimShort(err));
        taskRepository.save(t);
        logger.warn("ScheduledSkill 失败 task={} err={}", t.getName(), err);
    }

    private String trimShort(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private String buildTitle(ScheduledSkillTask t) {
        return "[定时] " + t.getName() + " @ " + LocalDateTime.now().toLocalDate();
    }

    private LocalDateTime computeNext(String cron, LocalDateTime from) {
        try {
            CronExpression expr = CronExpression.parse(cron);
            ZonedDateTime base = from.atZone(ZONE);
            ZonedDateTime next = expr.next(base);
            return next == null ? from.plusHours(1) : next.toLocalDateTime();
        } catch (Exception e) {
            logger.warn("无法解析 cron='{}'，默认延后 1 小时: {}", cron, e.getMessage());
            return from.plus(Duration.ofHours(1));
        }
    }
}
