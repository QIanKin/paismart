package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.repository.creator.CreatorAccountRepository;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern XHS_INTERNAL_USER_ID = Pattern.compile("^[0-9a-fA-F]{16,}$");
    private static final Pattern XHS_PROFILE_URL = Pattern.compile("/user/profile/([0-9a-fA-F]{16,})");

    private final CreatorAccountRepository accountRepo;
    private final CreatorService creatorService;
    private final TikhubXhsService tikhubService;

    public XhsRefreshService(CreatorAccountRepository accountRepo,
                             CreatorService creatorService,
                             TikhubXhsService tikhubService) {
        this.accountRepo = accountRepo;
        this.creatorService = creatorService;
        this.tikhubService = tikhubService;
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
        if (!tikhubService.configured()) {
            return Result.error("provider_disabled", "TikHub 未配置或未启用");
        }

        ResolvedUser resolved;
        try {
            resolved = resolveUserIdentity(acc);
        } catch (TikhubXhsService.ApiException e) {
            return Result.error(e.code(), e.getMessage());
        }

        TikhubXhsService.UserProfile profile;
        List<Map<String, Object>> rows;
        try {
            profile = tikhubService.fetchUserInfo(resolved.userId());
            rows = fetchUserPosts(resolved.userId(), safeLimit, cancelled);
        } catch (TikhubXhsService.ApiException e) {
            log.warn("TikHub 刷新失败 userId={} err={}", resolved.userId(), e.getMessage());
            return Result.error(e.code(), e.getMessage());
        }
        if (rows == null) rows = List.of();
        int fetched = rows.size();

        if (dryRun) {
            return new Result(true, accountId, fetched, 0, 0, 0,
                    rows.size() > 5 ? rows.subList(0, 5) : rows,
                    null, null, true);
        }

        CreatorService.PostBatchResult r = creatorService.upsertPosts(accountId, orgTag, rows);

        // 把账号级指标（粉丝 / 获赞 / 均赞 / 互动率 / 认证 / 头像 / 昵称）回写 creator_accounts
        try {
            applyAccountStats(acc, resolved, profile, rows);
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
                                   ResolvedUser resolved,
                                   TikhubXhsService.UserProfile profile,
                                   List<Map<String, Object>> posts) {
        Long followers = profile == null ? null : parseHumanCount(profile.fansText);
        Long following = profile == null ? null : parseHumanCount(profile.followingText);
        Long likesTotal = profile == null ? null : parseHumanCount(profile.likesText);
        Boolean verified = profile == null ? null : profile.verified;
        String verifyType = verified != null && verified ? "verified" : null;
        String displayName = profile == null ? null : profile.nickname;
        String avatarUrl = profile == null ? null : profile.avatar;
        String region = profile == null ? null : profile.ipLocation;
        String bio = profile == null ? null : profile.desc;

        // 从本次拉到的 posts 聚合均赞 / 均评 / engagement
        Long avgLikes = null, avgComments = null;
        Double engagementRate = null;
        Long postsCount = profile != null && profile.noteCount != null ? profile.noteCount
                : (posts != null ? (long) posts.size() : null);
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
        if (resolved != null && resolved.userId() != null && !resolved.userId().isBlank()) {
            acc.setPlatformUserId(resolved.userId());
        }
        if (profile != null && profile.redId != null && !profile.redId.isBlank()) {
            acc.setHandle(profile.redId);
        }
        if (displayName != null) acc.setDisplayName(displayName);
        if (avatarUrl != null) acc.setAvatarUrl(avatarUrl);
        if (bio != null) acc.setBio(bio);
        if (followers != null) acc.setFollowers(followers);
        if (following != null) acc.setFollowing(following);
        if (likesTotal != null) acc.setLikes(likesTotal);
        if (postsCount != null) acc.setPosts(postsCount);
        if (avgLikes != null) acc.setAvgLikes(avgLikes);
        if (avgComments != null) acc.setAvgComments(avgComments);
        if (engagementRate != null) acc.setEngagementRate(engagementRate);
        if (verified != null) acc.setVerified(verified);
        if (verifyType != null) acc.setVerifyType(verifyType);
        if (region != null) acc.setRegion(region);
        if (resolved != null && resolved.homepageUrl() != null && !resolved.homepageUrl().isBlank()) {
            acc.setHomepageUrl(resolved.homepageUrl());
        } else if (acc.getHomepageUrl() == null || acc.getHomepageUrl().isBlank()) {
            acc.setHomepageUrl("https://www.xiaohongshu.com/user/profile/" + acc.getPlatformUserId());
        }
        mergeCustomFields(acc, profile, posts);
        accountRepo.save(acc);
    }

    private void mergeCustomFields(CreatorAccount acc,
                                   TikhubXhsService.UserProfile profile,
                                   List<Map<String, Object>> posts) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("xhsSource", "tikhub");
        merged.put("tikhubLastRefreshAt", java.time.Instant.now().toString());
        if (profile != null) {
            putIfPresent(merged, "redId", profile.redId);
            putIfPresent(merged, "xhsUserId", profile.userId);
            putIfPresent(merged, "xhsNickname", profile.nickname);
            putIfPresent(merged, "xhsIpLocation", profile.ipLocation);
        }
        if (posts != null && !posts.isEmpty()) {
            Map<String, Object> first = posts.get(0);
            putIfPresent(merged, "latestNoteId", first.get("platformPostId"));
            putIfPresent(merged, "latestNoteTitle", first.get("title"));
            putIfPresent(merged, "latestNoteUrl", first.get("link"));
            putIfPresent(merged, "latestNoteType", first.get("postType"));
        }
        if (merged.size() <= 2) return;
        acc.setCustomFieldsJson(com.yizhaoqi.smartpai.service.creator.CustomFieldsMerger
                .merge(acc.getCustomFieldsJson(), toJson(merged)));
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).replaceAll("[,\\s]", "")); }
        catch (Exception e) { return null; }
    }
    private List<Map<String, Object>> fetchUserPosts(String userId, int limit, AtomicBoolean cancelled)
            throws TikhubXhsService.ApiException {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        String cursor = "";
        int remaining = limit;
        int loops = 0;
        while (remaining > 0 && loops < 20) {
            if (cancelled != null && cancelled.get()) {
                break;
            }
            TikhubXhsService.UserNotesResult page = tikhubService.fetchUserNotes(userId, cursor, Math.min(30, remaining));
            if (page.notes == null || page.notes.isEmpty()) {
                break;
            }
            for (TikhubXhsService.UserNote note : page.notes) {
                rows.add(toPostRow(note));
                if (rows.size() >= limit) break;
            }
            if (rows.size() >= limit || !page.hasMore || page.cursor == null || page.cursor.isBlank()
                    || page.cursor.equals(cursor)) {
                break;
            }
            cursor = page.cursor;
            remaining = limit - rows.size();
            loops++;
        }
        return rows;
    }

    private Map<String, Object> toPostRow(TikhubXhsService.UserNote note) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("platformPostId", note.noteId);
        row.put("postType", note.type);
        row.put("title", note.title);
        row.put("coverUrl", note.coverUrl);
        row.put("link", note.link);
        row.put("likes", note.likes);
        row.put("comments", note.comments);
        row.put("shares", note.shares);
        row.put("collects", note.collects);
        row.put("rawJson", toJson(note.raw));
        return row;
    }

    private static String toJson(Object raw) {
        if (raw == null) return null;
        try {
            return MAPPER.writeValueAsString(raw);
        } catch (Exception e) {
            return String.valueOf(raw);
        }
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value == null) return;
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) out.put(key, value);
    }

    private ResolvedUser resolveUserIdentity(CreatorAccount acc) throws TikhubXhsService.ApiException {
        String homepageUserId = extractUserIdFromHomepage(acc.getHomepageUrl());
        if (homepageUserId != null) {
            return new ResolvedUser(homepageUserId, "homepage_url",
                    "https://www.xiaohongshu.com/user/profile/" + homepageUserId);
        }
        String current = safeText(acc.getPlatformUserId());
        if (isInternalUserId(current)) {
            return new ResolvedUser(current, "platform_user_id",
                    "https://www.xiaohongshu.com/user/profile/" + current);
        }

        List<String> keywords = new java.util.ArrayList<>();
        addKeyword(keywords, acc.getPlatformUserId());
        addKeyword(keywords, acc.getHandle());
        addKeyword(keywords, acc.getDisplayName());

        TikhubXhsService.UserSummary fallback = null;
        for (String keyword : keywords) {
            TikhubXhsService.UserSearchResult search = tikhubService.searchUsers(keyword, 1);
            if (search.users == null || search.users.isEmpty()) continue;
            if (fallback == null) fallback = search.users.get(0);
            TikhubXhsService.UserSummary exact = pickBestUserMatch(acc, keyword, search.users);
            if (exact != null) {
                return new ResolvedUser(exact.userId, "search_exact",
                        "https://www.xiaohongshu.com/user/profile/" + exact.userId);
            }
        }
        if (fallback != null && fallback.userId != null && !fallback.userId.isBlank()) {
            return new ResolvedUser(fallback.userId, "search_first_result",
                    "https://www.xiaohongshu.com/user/profile/" + fallback.userId);
        }
        throw new TikhubXhsService.ApiException("user_not_resolved",
                "无法通过 TikHub 搜索定位该博主 userId，请补充更准确的小红书号/主页链接");
    }

    private static TikhubXhsService.UserSummary pickBestUserMatch(CreatorAccount acc,
                                                                  String keyword,
                                                                  List<TikhubXhsService.UserSummary> users) {
        if (users == null || users.isEmpty()) return null;
        String targetUid = safeText(acc.getPlatformUserId());
        String targetHandle = safeText(acc.getHandle());
        String targetName = safeText(acc.getDisplayName());
        String kw = safeText(keyword);
        for (TikhubXhsService.UserSummary user : users) {
            if (equalsIgnoreCase(user.userId, targetUid)) return user;
            if (equalsIgnoreCase(user.redId, targetUid)) return user;
            if (equalsIgnoreCase(user.redId, targetHandle)) return user;
            if (equalsIgnoreCase(user.nickname, targetName)) return user;
            if (equalsIgnoreCase(user.nickname, kw)) return user;
        }
        return null;
    }

    private static boolean isInternalUserId(String text) {
        return text != null && XHS_INTERNAL_USER_ID.matcher(text).matches();
    }

    private static String extractUserIdFromHomepage(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher m = XHS_PROFILE_URL.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static void addKeyword(List<String> keywords, String value) {
        String v = safeText(value);
        if (v.isBlank() || keywords.contains(v)) return;
        keywords.add(v);
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static Long parseHumanCount(String text) {
        if (text == null || text.isBlank()) return null;
        String raw = text.trim().replace(",", "");
        try {
            return Long.parseLong(raw);
        } catch (Exception ignored) {
            // continue
        }
        try {
            if (raw.endsWith("万")) {
                return Math.round(Double.parseDouble(raw.substring(0, raw.length() - 1)) * 10_000d);
            }
            if (raw.endsWith("千")) {
                return Math.round(Double.parseDouble(raw.substring(0, raw.length() - 1)) * 1_000d);
            }
        } catch (Exception ignored) {
            // continue
        }
        return null;
    }

    private record ResolvedUser(String userId, String source, String homepageUrl) {}

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
