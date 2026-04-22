package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.model.agent.Project;
import com.yizhaoqi.smartpai.model.agent.ProjectCreator;
import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.repository.agent.ProjectCreatorRepository;
import com.yizhaoqi.smartpai.repository.agent.ProjectRepository;
import com.yizhaoqi.smartpai.repository.creator.CreatorAccountRepository;
import com.yizhaoqi.smartpai.repository.creator.CreatorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 项目博主名册服务。支撑：
 *  - 前端项目详情页的"博主名册"Tab
 *  - Agent 的 ALLOCATION 会话（盘点 / 挑人 / 调整阶段）
 *  - 后台定时刷新任务（只刷「活跃阶段」名册里的博主）
 */
@Service
public class ProjectCreatorService {

    private static final Logger log = LoggerFactory.getLogger(ProjectCreatorService.class);

    /** 默认视为"活跃"的阶段，用于定时刷新 */
    public static final List<ProjectCreator.Stage> ACTIVE_STAGES = List.of(
            ProjectCreator.Stage.SHORTLISTED,
            ProjectCreator.Stage.LOCKED,
            ProjectCreator.Stage.SIGNED,
            ProjectCreator.Stage.PUBLISHED
    );

    private final ProjectCreatorRepository rosterRepo;
    private final ProjectRepository projectRepo;
    private final CreatorRepository creatorRepo;
    private final CreatorAccountRepository accountRepo;

    public ProjectCreatorService(ProjectCreatorRepository rosterRepo,
                                 ProjectRepository projectRepo,
                                 CreatorRepository creatorRepo,
                                 CreatorAccountRepository accountRepo) {
        this.rosterRepo = rosterRepo;
        this.projectRepo = projectRepo;
        this.creatorRepo = creatorRepo;
        this.accountRepo = accountRepo;
    }

    /** 列出某个项目的所有 roster 条目（附带 creator 主表字段，一次请求搞定）。 */
    @Transactional(readOnly = true)
    public List<RosterEntryView> listRoster(Long projectId, Long ownerUserId) {
        assertOwned(projectId, ownerUserId);
        List<ProjectCreator> rows = rosterRepo.findByProjectIdOrderByPriorityDescIdDesc(projectId);
        if (rows.isEmpty()) return List.of();
        // 一次性拉 Creator 字段，避免 N+1
        List<Long> creatorIds = rows.stream().map(ProjectCreator::getCreatorId).toList();
        Map<Long, Creator> creatorMap = new LinkedHashMap<>();
        creatorRepo.findAllById(creatorIds).forEach(c -> creatorMap.put(c.getId(), c));
        List<RosterEntryView> out = new ArrayList<>(rows.size());
        for (ProjectCreator pc : rows) {
            out.add(new RosterEntryView(pc, creatorMap.get(pc.getCreatorId())));
        }
        return out;
    }

    @Transactional
    public ProjectCreator addToRoster(Long projectId, Long ownerUserId, Long creatorId,
                                      ProjectCreator.Stage stage, Integer priority,
                                      BigDecimal quotedPrice, String currency,
                                      Long assignedToUserId, String projectNotes,
                                      String addedBy) {
        Project project = assertOwned(projectId, ownerUserId);
        Creator creator = creatorRepo.findById(creatorId)
                .filter(c -> c.getOwnerOrgTag() != null && c.getOwnerOrgTag().equals(project.getOrgTag()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "creator 不存在或与项目不属于同一租户: " + creatorId));
        Optional<ProjectCreator> existing = rosterRepo.findByProjectIdAndCreatorId(projectId, creatorId);
        ProjectCreator pc = existing.orElseGet(ProjectCreator::new);
        if (existing.isEmpty()) {
            pc.setProjectId(projectId);
            pc.setCreatorId(creatorId);
            pc.setOwnerOrgTag(project.getOrgTag());
            pc.setAddedBy(addedBy);
        }
        if (stage != null) pc.setStage(stage);
        if (priority != null) pc.setPriority(Math.max(1, Math.min(priority, 100)));
        if (quotedPrice != null) pc.setQuotedPrice(quotedPrice);
        if (currency != null) pc.setCurrency(currency);
        if (assignedToUserId != null) pc.setAssignedToUserId(assignedToUserId);
        if (projectNotes != null) pc.setProjectNotes(projectNotes);
        return rosterRepo.save(pc);
    }

    @Transactional
    public ProjectCreator updateStage(Long rosterId, Long ownerUserId, ProjectCreator.Stage stage) {
        ProjectCreator pc = assertRosterOwned(rosterId, ownerUserId);
        if (stage == null) throw new IllegalArgumentException("stage 不能为空");
        pc.setStage(stage);
        return rosterRepo.save(pc);
    }

    @Transactional
    public ProjectCreator updateEntry(Long rosterId, Long ownerUserId,
                                      ProjectCreator.Stage stage, Integer priority,
                                      BigDecimal quotedPrice, String currency,
                                      Long assignedToUserId, String projectNotes) {
        return updateEntry(rosterId, ownerUserId, stage, priority, quotedPrice, currency,
                assignedToUserId, projectNotes, null);
    }

    /** 扩展版：支持写入 customFieldsJson（用户在「名册条目字段」里定义的自定义字段）。 */
    @Transactional
    public ProjectCreator updateEntry(Long rosterId, Long ownerUserId,
                                      ProjectCreator.Stage stage, Integer priority,
                                      BigDecimal quotedPrice, String currency,
                                      Long assignedToUserId, String projectNotes,
                                      String customFieldsJson) {
        ProjectCreator pc = assertRosterOwned(rosterId, ownerUserId);
        if (stage != null) pc.setStage(stage);
        if (priority != null) pc.setPriority(Math.max(1, Math.min(priority, 100)));
        if (quotedPrice != null) pc.setQuotedPrice(quotedPrice);
        if (currency != null) pc.setCurrency(currency);
        if (assignedToUserId != null) pc.setAssignedToUserId(assignedToUserId);
        if (projectNotes != null) pc.setProjectNotes(projectNotes);
        if (customFieldsJson != null) pc.setCustomFieldsJson(customFieldsJson.isBlank() ? null : customFieldsJson);
        return rosterRepo.save(pc);
    }

    @Transactional
    public void remove(Long rosterId, Long ownerUserId) {
        ProjectCreator pc = assertRosterOwned(rosterId, ownerUserId);
        rosterRepo.delete(pc);
    }

    /** 批量加入：已存在的按 upsert 逻辑合并，不抛错。 */
    @Transactional
    public List<ProjectCreator> addBatch(Long projectId, Long ownerUserId, List<Long> creatorIds,
                                         ProjectCreator.Stage stage, String addedBy) {
        Project project = assertOwned(projectId, ownerUserId);
        if (creatorIds == null || creatorIds.isEmpty()) return List.of();
        List<Creator> creators = creatorRepo.findAllById(creatorIds).stream()
                .filter(c -> c.getOwnerOrgTag() != null && c.getOwnerOrgTag().equals(project.getOrgTag()))
                .toList();
        List<ProjectCreator> saved = new ArrayList<>(creators.size());
        for (Creator c : creators) {
            Optional<ProjectCreator> existing = rosterRepo.findByProjectIdAndCreatorId(projectId, c.getId());
            ProjectCreator pc = existing.orElseGet(ProjectCreator::new);
            if (existing.isEmpty()) {
                pc.setProjectId(projectId);
                pc.setCreatorId(c.getId());
                pc.setOwnerOrgTag(project.getOrgTag());
                pc.setAddedBy(addedBy);
                pc.setStage(stage == null ? ProjectCreator.Stage.CANDIDATE : stage);
            } else if (stage != null) {
                pc.setStage(stage);
            }
            saved.add(rosterRepo.save(pc));
        }
        return saved;
    }

    /**
     * 按 <strong>账号</strong>（CreatorAccount）批量加入名册。
     *
     * <p>流程：对每个 accountId，若其 {@code creatorId==null} 就<strong>即时为它造一个 Creator（人）</strong>
     * 并回写 account.creatorId；然后用得到的 creatorId 去 {@link #addBatch} upsert 名册。</p>
     *
     * <p>这条路径是给"博主库（账号视图）→ 项目名册"用的 —— 用户/Agent 看到的是 CreatorAccount，
     * 不用关心 Creator（人）表什么时候出现。</p>
     *
     * @return 新建/合并出的 ProjectCreator 条目列表
     */
    @Transactional
    public List<ProjectCreator> addAccountsBatch(Long projectId, Long ownerUserId, List<Long> accountIds,
                                                 ProjectCreator.Stage stage, String addedBy) {
        Project project = assertOwned(projectId, ownerUserId);
        if (accountIds == null || accountIds.isEmpty()) return List.of();
        List<Long> creatorIds = new ArrayList<>(accountIds.size());
        for (Long accountId : accountIds) {
            if (accountId == null) continue;
            CreatorAccount acc = accountRepo.findById(accountId).orElse(null);
            if (acc == null) {
                log.warn("[roster] 跳过不存在的 accountId={}", accountId);
                continue;
            }
            if (acc.getOwnerOrgTag() == null || !acc.getOwnerOrgTag().equals(project.getOrgTag())) {
                log.warn("[roster] 跳过跨租户 accountId={} accountOrg={} projectOrg={}",
                        accountId, acc.getOwnerOrgTag(), project.getOrgTag());
                continue;
            }
            Long creatorId = acc.getCreatorId();
            if (creatorId == null) {
                Creator c = materializeCreatorFromAccount(acc, project.getOrgTag());
                creatorId = c.getId();
                acc.setCreatorId(creatorId);
                accountRepo.save(acc);
                log.info("[roster] 为 account #{} 即时生成 creator #{}", accountId, creatorId);
            }
            creatorIds.add(creatorId);
        }
        if (creatorIds.isEmpty()) return List.of();
        return addBatch(projectId, ownerUserId, creatorIds, stage, addedBy);
    }

    /** 为一个 CreatorAccount 即时 materialize 出一条 Creator（人）记录。 */
    private Creator materializeCreatorFromAccount(CreatorAccount acc, String orgTag) {
        Creator c = new Creator();
        c.setOwnerOrgTag(orgTag);
        String name = acc.getDisplayName();
        if (name == null || name.isBlank()) name = acc.getHandle();
        if (name == null || name.isBlank()) name = "Creator_" + acc.getPlatformUserId();
        c.setDisplayName(name);
        return creatorRepo.save(c);
    }

    @Transactional(readOnly = true)
    public List<Long> findActiveCreatorIdsForOrg(String orgTag) {
        return rosterRepo.findDistinctCreatorIdByOrgTagAndStages(orgTag, ACTIVE_STAGES);
    }

    /**
     * 聚合：一个 creator 在哪些项目里、什么阶段。
     * 在博主详情抽屉给用户看"该博主在哪些项目上用了"。
     */
    @Transactional(readOnly = true)
    public List<ProjectCreator> listProjectsOfCreator(Long creatorId) {
        return rosterRepo.findByCreatorId(creatorId);
    }

    private Project assertOwned(Long projectId, Long ownerUserId) {
        Project p = projectRepo.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
        if (p.getOwnerUserId() == null || !p.getOwnerUserId().equals(ownerUserId)) {
            throw new IllegalArgumentException("无权访问项目: " + projectId);
        }
        return p;
    }

    private ProjectCreator assertRosterOwned(Long rosterId, Long ownerUserId) {
        ProjectCreator pc = rosterRepo.findById(rosterId)
                .orElseThrow(() -> new IllegalArgumentException("roster 不存在: " + rosterId));
        assertOwned(pc.getProjectId(), ownerUserId);
        return pc;
    }

    /** 给前端用的 flat view：把 creator 常用字段带出来，少一次 join 请求。 */
    public record RosterEntryView(ProjectCreator entry, Creator creator) {}
}
