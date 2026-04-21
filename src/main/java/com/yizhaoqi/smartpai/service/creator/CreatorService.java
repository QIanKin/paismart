package com.yizhaoqi.smartpai.service.creator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.model.creator.CreatorPost;
import com.yizhaoqi.smartpai.model.creator.CreatorSnapshot;
import com.yizhaoqi.smartpai.repository.creator.CreatorAccountRepository;
import com.yizhaoqi.smartpai.repository.creator.CreatorPostRepository;
import com.yizhaoqi.smartpai.repository.creator.CreatorRepository;
import com.yizhaoqi.smartpai.repository.creator.CreatorSnapshotRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 博主数据库的核心 service：
 *  - search：分页 + 过滤（platform / keyword / followers 区间 / tags contains / cooperationStatus）
 *  - upsert：按 platform+platformUserId 去重 upsert 账号；按 orgTag+displayName upsert Creator；
 *  - custom fields：调用 {@link CustomFieldsMerger} 做 JSON 合并；
 *  - snapshot：追加快照并把 account 的 latest 字段回写。
 */
@Service
public class CreatorService {

    private static final Logger logger = LoggerFactory.getLogger(CreatorService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CreatorRepository creatorRepo;
    private final CreatorAccountRepository accountRepo;
    private final CreatorPostRepository postRepo;
    private final CreatorSnapshotRepository snapshotRepo;

    public CreatorService(CreatorRepository creatorRepo,
                          CreatorAccountRepository accountRepo,
                          CreatorPostRepository postRepo,
                          CreatorSnapshotRepository snapshotRepo) {
        this.creatorRepo = creatorRepo;
        this.accountRepo = accountRepo;
        this.postRepo = postRepo;
        this.snapshotRepo = snapshotRepo;
    }

    // ---------- Creator ----------

    @Transactional
    public Creator upsertCreator(CreatorUpsertRequest req) {
        if (req.ownerOrgTag() == null || req.ownerOrgTag().isBlank()) {
            throw new IllegalArgumentException("ownerOrgTag 必填");
        }
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw new IllegalArgumentException("displayName 必填");
        }
        Creator existing = null;
        if (req.id() != null) {
            existing = creatorRepo.findById(req.id()).orElse(null);
            if (existing != null && !existing.getOwnerOrgTag().equals(req.ownerOrgTag())) {
                throw new IllegalArgumentException("Creator #" + req.id() + " 不属于当前租户");
            }
        } else {
            List<Creator> dupes = creatorRepo.findByOwnerOrgTagAndDisplayName(
                    req.ownerOrgTag(), req.displayName());
            if (!dupes.isEmpty()) existing = dupes.get(0);
        }
        Creator c = existing == null ? new Creator() : existing;
        if (existing == null) {
            c.setOwnerOrgTag(req.ownerOrgTag());
            c.setDisplayName(req.displayName());
        }
        if (req.realName() != null) c.setRealName(req.realName());
        if (req.gender() != null) c.setGender(req.gender());
        if (req.birthYear() != null) c.setBirthYear(req.birthYear());
        if (req.city() != null) c.setCity(req.city());
        if (req.country() != null) c.setCountry(req.country());
        if (req.personaTagsJson() != null) c.setPersonaTagsJson(req.personaTagsJson());
        if (req.trackTagsJson() != null) c.setTrackTagsJson(req.trackTagsJson());
        if (req.cooperationStatus() != null) c.setCooperationStatus(req.cooperationStatus());
        if (req.internalOwnerId() != null) c.setInternalOwnerId(req.internalOwnerId());
        if (req.internalNotes() != null) c.setInternalNotes(req.internalNotes());
        if (req.priceNote() != null) c.setPriceNote(req.priceNote());
        if (req.customFieldsJson() != null) {
            c.setCustomFieldsJson(CustomFieldsMerger.merge(c.getCustomFieldsJson(), req.customFieldsJson()));
        }
        if (c.getCreatedBy() == null && req.createdBy() != null) c.setCreatedBy(req.createdBy());
        return creatorRepo.save(c);
    }

    @Transactional(readOnly = true)
    public Page<Creator> searchCreators(CreatorSearchQuery q, Pageable pageable) {
        Specification<Creator> spec = (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("ownerOrgTag"), q.ownerOrgTag()));
            if (q.keyword() != null && !q.keyword().isBlank()) {
                String like = "%" + q.keyword().trim() + "%";
                ps.add(cb.or(
                        cb.like(root.get("displayName"), like),
                        cb.like(cb.coalesce(root.get("realName"), ""), like),
                        cb.like(cb.coalesce(root.get("internalNotes"), ""), like)));
            }
            if (q.cooperationStatus() != null && !q.cooperationStatus().isBlank()) {
                ps.add(cb.equal(root.get("cooperationStatus"), q.cooperationStatus()));
            }
            if (q.tagContains() != null && !q.tagContains().isBlank()) {
                String like = "%" + q.tagContains().trim() + "%";
                ps.add(cb.or(
                        cb.like(cb.coalesce(root.get("personaTagsJson"), ""), like),
                        cb.like(cb.coalesce(root.get("trackTagsJson"), ""), like)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return creatorRepo.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Optional<Creator> getCreator(Long id, String ownerOrgTag) {
        return creatorRepo.findById(id)
                .filter(c -> c.getOwnerOrgTag().equals(ownerOrgTag));
    }

    @Transactional
    public boolean deleteCreator(Long id, String ownerOrgTag) {
        return creatorRepo.findById(id).filter(c -> c.getOwnerOrgTag().equals(ownerOrgTag))
                .map(c -> { creatorRepo.delete(c); return true; }).orElse(false);
    }

    // ---------- Account ----------

    @Transactional
    public CreatorAccount upsertAccount(CreatorAccountUpsertRequest req) {
        if (req.ownerOrgTag() == null || req.ownerOrgTag().isBlank())
            throw new IllegalArgumentException("ownerOrgTag 必填");
        if (req.platform() == null || req.platform().isBlank())
            throw new IllegalArgumentException("platform 必填");
        if (req.platformUserId() == null || req.platformUserId().isBlank())
            throw new IllegalArgumentException("platformUserId 必填");

        CreatorAccount a = accountRepo.findByPlatformAndPlatformUserId(req.platform(), req.platformUserId())
                .orElseGet(CreatorAccount::new);
        boolean isNew = a.getId() == null;
        if (isNew) {
            a.setPlatform(req.platform());
            a.setPlatformUserId(req.platformUserId());
            a.setOwnerOrgTag(req.ownerOrgTag());
        } else if (!a.getOwnerOrgTag().equals(req.ownerOrgTag())) {
            throw new IllegalArgumentException("账号已存在且属于其他租户");
        }
        if (req.creatorId() != null) a.setCreatorId(req.creatorId());
        if (req.handle() != null) a.setHandle(req.handle());
        if (req.displayName() != null) a.setDisplayName(req.displayName());
        if (req.avatarUrl() != null) a.setAvatarUrl(req.avatarUrl());
        if (req.bio() != null) a.setBio(req.bio());
        if (req.followers() != null) a.setFollowers(req.followers());
        if (req.following() != null) a.setFollowing(req.following());
        if (req.likes() != null) a.setLikes(req.likes());
        if (req.posts() != null) a.setPosts(req.posts());
        if (req.avgLikes() != null) a.setAvgLikes(req.avgLikes());
        if (req.avgComments() != null) a.setAvgComments(req.avgComments());
        if (req.hitRatio() != null) a.setHitRatio(req.hitRatio());
        if (req.engagementRate() != null) a.setEngagementRate(req.engagementRate());
        if (req.verified() != null) a.setVerified(req.verified());
        if (req.verifyType() != null) a.setVerifyType(req.verifyType());
        if (req.region() != null) a.setRegion(req.region());
        if (req.homepageUrl() != null) a.setHomepageUrl(req.homepageUrl());
        if (req.categoryMain() != null) a.setCategoryMain(req.categoryMain());
        if (req.categorySub() != null) a.setCategorySub(req.categorySub());
        if (req.platformTagsJson() != null) a.setPlatformTagsJson(req.platformTagsJson());
        if (req.customFieldsJson() != null) {
            a.setCustomFieldsJson(CustomFieldsMerger.merge(a.getCustomFieldsJson(), req.customFieldsJson()));
        }
        return accountRepo.save(a);
    }

    @Transactional(readOnly = true)
    public Page<CreatorAccount> searchAccounts(AccountSearchQuery q, Pageable pageable) {
        Specification<CreatorAccount> spec = (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("ownerOrgTag"), q.ownerOrgTag()));
            if (q.platform() != null && !q.platform().isBlank()) {
                ps.add(cb.equal(root.get("platform"), q.platform()));
            }
            if (q.keyword() != null && !q.keyword().isBlank()) {
                String like = "%" + q.keyword().trim() + "%";
                ps.add(cb.or(
                        cb.like(cb.coalesce(root.get("displayName"), ""), like),
                        cb.like(cb.coalesce(root.get("handle"), ""), like),
                        cb.like(cb.coalesce(root.get("bio"), ""), like)));
            }
            if (q.categoryMain() != null && !q.categoryMain().isBlank()) {
                ps.add(cb.equal(root.get("categoryMain"), q.categoryMain()));
            }
            if (q.followersMin() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("followers").as(Long.class), q.followersMin()));
            }
            if (q.followersMax() != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("followers").as(Long.class), q.followersMax()));
            }
            if (q.verifiedOnly() != null && q.verifiedOnly()) {
                ps.add(cb.isTrue(root.get("verified")));
            }
            if (q.creatorId() != null) {
                ps.add(cb.equal(root.get("creatorId"), q.creatorId()));
            }
            if (q.tagContains() != null && !q.tagContains().isBlank()) {
                String like = "%" + q.tagContains().trim() + "%";
                ps.add(cb.like(cb.coalesce(root.get("platformTagsJson"), ""), like));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return accountRepo.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Optional<CreatorAccount> getAccount(Long id, String ownerOrgTag) {
        return accountRepo.findById(id).filter(a -> a.getOwnerOrgTag().equals(ownerOrgTag));
    }

    @Transactional(readOnly = true)
    public List<CreatorAccount> getAccountsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return accountRepo.findAllById(ids);
    }

    /** 按 creatorId + orgTag 查这个博主的所有平台账号（博主详情抽屉 / agent 上下文用）。 */
    @Transactional(readOnly = true)
    public List<CreatorAccount> getAccountsByCreator(Long creatorId, String ownerOrgTag) {
        return accountRepo.findByCreatorId(creatorId).stream()
                .filter(a -> a.getOwnerOrgTag().equals(ownerOrgTag))
                .toList();
    }

    // ---------- Post ----------

    @Transactional
    public PostBatchResult upsertPosts(Long accountId, String ownerOrgTag, List<Map<String, Object>> rawPosts) {
        CreatorAccount account = accountRepo.findById(accountId)
                .filter(a -> a.getOwnerOrgTag().equals(ownerOrgTag))
                .orElseThrow(() -> new IllegalArgumentException("account 不存在或越权: " + accountId));
        int inserted = 0, updated = 0, skipped = 0;
        for (Map<String, Object> row : rawPosts) {
            String platformPostId = str(row, "platformPostId");
            if (platformPostId == null || platformPostId.isBlank()) { skipped++; continue; }
            CreatorPost p = postRepo.findByPlatformAndPlatformPostId(account.getPlatform(), platformPostId)
                    .orElseGet(CreatorPost::new);
            boolean isNew = p.getId() == null;
            if (isNew) {
                p.setAccountId(accountId);
                p.setOwnerOrgTag(ownerOrgTag);
                p.setPlatform(account.getPlatform());
                p.setPlatformPostId(platformPostId);
            }
            if (row.containsKey("postType")) p.setPostType(str(row, "postType"));
            if (row.containsKey("title")) p.setTitle(str(row, "title"));
            if (row.containsKey("contentText")) p.setContentText(str(row, "contentText"));
            if (row.containsKey("coverUrl")) p.setCoverUrl(str(row, "coverUrl"));
            if (row.containsKey("videoUrl")) p.setVideoUrl(str(row, "videoUrl"));
            if (row.containsKey("link")) p.setLink(str(row, "link"));
            if (row.containsKey("publishedAt")) {
                String s = str(row, "publishedAt");
                if (s != null && !s.isBlank()) try { p.setPublishedAt(LocalDateTime.parse(s)); } catch (Exception ignored) {}
            }
            if (row.containsKey("durationSec")) p.setDurationSec(asInt(row.get("durationSec")));
            if (row.containsKey("likes")) p.setLikes(asLong(row.get("likes")));
            if (row.containsKey("comments")) p.setComments(asLong(row.get("comments")));
            if (row.containsKey("shares")) p.setShares(asLong(row.get("shares")));
            if (row.containsKey("collects")) p.setCollects(asLong(row.get("collects")));
            if (row.containsKey("views")) p.setViews(asLong(row.get("views")));
            if (row.containsKey("isHit")) p.setIsHit(Boolean.TRUE.equals(row.get("isHit")));
            if (row.containsKey("hashtags")) p.setHashtagsJson(toJsonStr(row.get("hashtags")));
            if (row.containsKey("hitStructureTags")) p.setHitStructureTagsJson(toJsonStr(row.get("hitStructureTags")));
            if (row.containsKey("rawJson")) p.setRawJson(toJsonStr(row.get("rawJson")));
            p.setMetricsSnapshotAt(LocalDateTime.now());
            postRepo.save(p);
            if (isNew) inserted++; else updated++;
        }
        return new PostBatchResult(inserted, updated, skipped);
    }

    // ---------- Snapshot ----------

    @Transactional
    public CreatorSnapshot addSnapshot(Long accountId, String ownerOrgTag, CreatorSnapshot s) {
        CreatorAccount a = accountRepo.findById(accountId)
                .filter(x -> x.getOwnerOrgTag().equals(ownerOrgTag))
                .orElseThrow(() -> new IllegalArgumentException("account 不存在或越权: " + accountId));
        if (s.getSnapshotAt() == null) s.setSnapshotAt(LocalDateTime.now());
        s.setAccountId(accountId);
        CreatorSnapshot saved = snapshotRepo.save(s);
        // 回写 account 的最新字段
        if (saved.getFollowers() != null) a.setFollowers(saved.getFollowers());
        if (saved.getFollowing() != null) a.setFollowing(saved.getFollowing());
        if (saved.getLikes() != null) a.setLikes(saved.getLikes());
        if (saved.getPosts() != null) a.setPosts(saved.getPosts());
        if (saved.getAvgLikes() != null) a.setAvgLikes(saved.getAvgLikes());
        if (saved.getAvgComments() != null) a.setAvgComments(saved.getAvgComments());
        if (saved.getHitRatio() != null) a.setHitRatio(saved.getHitRatio());
        if (saved.getEngagementRate() != null) a.setEngagementRate(saved.getEngagementRate());
        a.setLatestSnapshotAt(saved.getSnapshotAt());
        accountRepo.save(a);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CreatorPost> latestPostsOf(Long accountId) {
        return postRepo.findTop20ByAccountIdOrderByPublishedAtDesc(accountId);
    }

    /** 按 accountId 分页拉笔记（后端 UI 的「最近笔记」Tab 用）。 */
    @Transactional(readOnly = true)
    public Page<CreatorPost> pagedPostsOf(Long accountId, Pageable pageable) {
        return postRepo.findByAccountIdOrderByPublishedAtDesc(accountId, pageable);
    }

    /**
     * 读笔记的 TTL 缓存判定：snapshot 早于 threshold 就算过期。
     * Agent 工具会用它决定「用缓存还是现去爬」。
     */
    @Transactional(readOnly = true)
    public CachedPostsResult readPostsWithFreshness(Long accountId, int limit, java.time.Duration ttl) {
        List<CreatorPost> posts = postRepo.findTop20ByAccountIdOrderByPublishedAtDesc(accountId);
        if (posts.size() > Math.max(1, limit)) {
            posts = posts.subList(0, limit);
        }
        LocalDateTime mostRecent = null;
        for (CreatorPost p : posts) {
            LocalDateTime t = p.getMetricsSnapshotAt();
            if (t != null && (mostRecent == null || t.isAfter(mostRecent))) mostRecent = t;
        }
        boolean stale;
        if (posts.isEmpty() || mostRecent == null) {
            stale = true;
        } else {
            stale = mostRecent.isBefore(LocalDateTime.now().minus(ttl));
        }
        return new CachedPostsResult(posts, mostRecent, stale);
    }

    public record CachedPostsResult(List<CreatorPost> posts, LocalDateTime mostRecentSnapshotAt, boolean stale) {}

    // ---------- helpers ----------

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return null; }
    }
    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }
    private static String toJsonStr(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        try { return MAPPER.writeValueAsString(v); } catch (Exception e) { return String.valueOf(v); }
    }

    public static Pageable defaultPageable(int page, int size, String sortBy) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size <= 0 ? 20 : size, 200));
        if (sortBy == null || sortBy.isBlank()) {
            return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id"));
        }
        Sort.Direction dir = Sort.Direction.DESC;
        String key = sortBy;
        if (sortBy.startsWith("-")) { dir = Sort.Direction.DESC; key = sortBy.substring(1); }
        else if (sortBy.startsWith("+")) { dir = Sort.Direction.ASC; key = sortBy.substring(1); }
        else if (sortBy.endsWith(":asc")) { dir = Sort.Direction.ASC; key = sortBy.substring(0, sortBy.length() - 4); }
        else if (sortBy.endsWith(":desc")) { dir = Sort.Direction.DESC; key = sortBy.substring(0, sortBy.length() - 5); }
        return PageRequest.of(safePage, safeSize, Sort.by(dir, key));
    }

    // ---------- DTOs ----------

    public record CreatorUpsertRequest(
            Long id,
            String ownerOrgTag,
            String displayName,
            String realName,
            String gender,
            Integer birthYear,
            String city,
            String country,
            String personaTagsJson,
            String trackTagsJson,
            String cooperationStatus,
            Long internalOwnerId,
            String internalNotes,
            String priceNote,
            String customFieldsJson,
            String createdBy
    ) {}

    public record CreatorAccountUpsertRequest(
            Long creatorId,
            String ownerOrgTag,
            String platform,
            String platformUserId,
            String handle,
            String displayName,
            String avatarUrl,
            String bio,
            Long followers,
            Long following,
            Long likes,
            Long posts,
            Long avgLikes,
            Long avgComments,
            Double hitRatio,
            Double engagementRate,
            Boolean verified,
            String verifyType,
            String region,
            String homepageUrl,
            String categoryMain,
            String categorySub,
            String platformTagsJson,
            String customFieldsJson
    ) {}

    public record CreatorSearchQuery(
            String ownerOrgTag,
            String keyword,
            String cooperationStatus,
            String tagContains
    ) {}

    public record AccountSearchQuery(
            String ownerOrgTag,
            String platform,
            String keyword,
            String categoryMain,
            Long followersMin,
            Long followersMax,
            Boolean verifiedOnly,
            Long creatorId,
            String tagContains
    ) {}

    public record PostBatchResult(int inserted, int updated, int skipped) {}
}
