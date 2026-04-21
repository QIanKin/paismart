package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.repository.creator.CreatorAccountRepository;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "一键刷新 XHS 博主"的核心服务，供：
 *  - REST 接口 {@code POST /api/v1/creators/accounts/{id}/refresh:xhs}
 *  - Agent 工具 {@link com.yizhaoqi.smartpai.service.tool.builtin.XhsRefreshCreatorTool}
 *
 * 两边复用避免"拉数据 → 入库"的链路漂移。
 */
@Service
public class XhsRefreshService {

    private static final Logger log = LoggerFactory.getLogger(XhsRefreshService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CreatorAccountRepository accountRepo;
    private final CreatorService creatorService;
    private final XhsSkillRunner runner;

    public XhsRefreshService(CreatorAccountRepository accountRepo,
                             CreatorService creatorService,
                             XhsSkillRunner runner) {
        this.accountRepo = accountRepo;
        this.creatorService = creatorService;
        this.runner = runner;
    }

    /**
     * 刷新指定 account 最近 N 条笔记。
     *
     * @param accountId  CreatorAccount 主键
     * @param orgTag     JWT 解析的租户 tag，用于越权校验
     * @param limit      希望拉取的笔记数（会 clamp 到 1-200）
     * @param dryRun     true 只拉取不入库
     * @param sessionId  用于 sandbox 隔离，可传会话 id / 随机 id
     * @param cancelled  可选的取消信号
     */
    public Result refreshAccount(Long accountId, String orgTag, int limit, boolean dryRun,
                                 String sessionId, AtomicBoolean cancelled) {
        if (orgTag == null || orgTag.isBlank()) {
            return Result.error("org_missing", "orgTag 缺失");
        }
        if (accountId == null || accountId <= 0) {
            return Result.error("bad_account", "accountId 必填");
        }
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 200));

        Optional<CreatorAccount> accOpt = accountRepo.findById(accountId)
                .filter(a -> orgTag.equals(a.getOwnerOrgTag()));
        if (accOpt.isEmpty()) {
            return Result.error("not_found", "account 不存在或越权: " + accountId);
        }
        CreatorAccount acc = accOpt.get();
        if (!"xhs".equalsIgnoreCase(acc.getPlatform())) {
            return Result.error("platform_mismatch",
                    "仅支持 platform=xhs，当前=" + acc.getPlatform());
        }
        String uid = acc.getPlatformUserId();
        if (uid == null || uid.isBlank()) {
            return Result.error("no_platform_uid", "account 未填 platformUserId");
        }

        XhsSkillRunner.RunRequest req = new XhsSkillRunner.RunRequest();
        req.orgTag = orgTag;
        req.sessionId = sessionId;
        req.skillName = "xhs-user-notes";
        req.scriptRelative = "scripts/fetch_user_notes.py";
        req.cookiePlatform = "xhs_pc";
        req.extraArgs.add("--user-id");
        req.extraArgs.add(uid);
        req.extraArgs.add("--limit");
        req.extraArgs.add(String.valueOf(safeLimit));
        req.timeoutSeconds = 180;
        req.cancelled = cancelled;

        XhsSkillRunner.RunResult res = runner.run(req);
        if (!res.ok()) {
            return Result.error(
                    res.errorType() == null ? "skill_failed" : res.errorType(),
                    res.errorMessage());
        }

        JsonNode payload = res.payload();
        int fetched = payload.path("fetched").asInt(0);
        JsonNode postsNode = payload.path("posts");
        List<Map<String, Object>> rows;
        try {
            rows = MAPPER.convertValue(postsNode, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("posts 解析失败 uid={} err={}", uid, e.getMessage());
            return Result.error("parse_failed", "posts 解析失败: " + e.getMessage());
        }
        if (rows == null) rows = List.of();

        JsonNode userStatsNode = payload.path("userStats");
        Map<String, Object> userStats = userStatsNode.isObject()
                ? MAPPER.convertValue(userStatsNode, new TypeReference<Map<String, Object>>() {})
                : null;

        if (dryRun) {
            return new Result(true, accountId, fetched, 0, 0, 0,
                    rows.size() > 5 ? rows.subList(0, 5) : rows,
                    null, null, true);
        }

        CreatorService.PostBatchResult r = creatorService.upsertPosts(accountId, orgTag, rows);

        // 把账号级指标（粉丝 / 获赞 / 均赞 / 互动率 / 认证 / 头像 / 昵称）回写 creator_accounts
        try {
            applyAccountStats(acc, rows, userStats);
        } catch (Exception e) {
            log.warn("回填 account 统计失败 accountId={} err={}", accountId, e.getMessage());
        }

        return new Result(true, accountId, fetched,
                r.inserted(), r.updated(), r.skipped(),
                null, null, null, false);
    }

    /**
     * 把本次刷新的 userStats（来自 /user/otherinfo）和最近 N 条笔记的聚合，
     * 回填到 {@link CreatorAccount} 上。不覆盖 null：只在本次能算出值时才 set。
     */
    private void applyAccountStats(CreatorAccount acc,
                                   List<Map<String, Object>> posts,
                                   Map<String, Object> userStats) {
        Long followers = null, following = null, likesTotal = null;
        Boolean verified = null;
        String verifyType = null;
        String displayName = null;
        String avatarUrl = null;
        String region = null;
        String bio = null;
        if (userStats != null) {
            followers = asLong(userStats.get("followers"));
            following = asLong(userStats.get("following"));
            likesTotal = asLong(userStats.get("likesAndCollects"));
            Object v = userStats.get("verified");
            if (v instanceof Boolean b) verified = b;
            else if (v != null) verified = Boolean.parseBoolean(String.valueOf(v));
            verifyType = str(userStats.get("verifyType"));
            displayName = str(userStats.get("nickname"));
            avatarUrl = str(userStats.get("avatarUrl"));
            region = str(userStats.get("ipLocation"));
            bio = str(userStats.get("desc"));
        }

        // 从本次拉到的 posts 聚合均赞 / 均评 / engagement
        Long avgLikes = null, avgComments = null;
        Double engagementRate = null;
        Long postsCount = posts != null ? (long) posts.size() : null;
        if (posts != null && !posts.isEmpty()) {
            long sumLikes = 0, sumComments = 0, sumCollects = 0, sumShares = 0;
            int n = 0;
            for (Map<String, Object> p : posts) {
                Long li = asLong(p.get("likes"));
                Long co = asLong(p.get("comments"));
                Long ck = asLong(p.get("collects"));
                Long sh = asLong(p.get("shares"));
                sumLikes += li != null ? li : 0;
                sumComments += co != null ? co : 0;
                sumCollects += ck != null ? ck : 0;
                sumShares += sh != null ? sh : 0;
                n++;
            }
            if (n > 0) {
                avgLikes = sumLikes / n;
                avgComments = sumComments / n;
            }
            if (followers != null && followers > 0 && n > 0) {
                double total = sumLikes + sumComments + sumCollects + sumShares;
                engagementRate = (total / n) / followers.doubleValue();
            }
        }

        CreatorService.CreatorAccountUpsertRequest up = new CreatorService.CreatorAccountUpsertRequest(
                acc.getCreatorId(),
                acc.getOwnerOrgTag(),
                acc.getPlatform(),
                acc.getPlatformUserId(),
                acc.getHandle(),
                displayName != null ? displayName : acc.getDisplayName(),
                avatarUrl,
                bio,
                followers,
                following,
                likesTotal,
                postsCount,
                avgLikes,
                avgComments,
                null,
                engagementRate,
                verified,
                verifyType,
                region,
                acc.getHomepageUrl(),
                null,
                null,
                null,
                null);
        creatorService.upsertAccount(up);
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).replaceAll("[,\\s]", "")); }
        catch (Exception e) { return null; }
    }
    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    public record Result(
            boolean ok,
            Long accountId,
            int fetched,
            int inserted,
            int updated,
            int skipped,
            List<Map<String, Object>> preview,
            String errorType,
            String errorMessage,
            boolean dryRun
    ) {
        public static Result error(String type, String message) {
            return new Result(false, null, 0, 0, 0, 0, null, type, message, false);
        }
    }
}
