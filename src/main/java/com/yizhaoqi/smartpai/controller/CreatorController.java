package com.yizhaoqi.smartpai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.model.creator.CreatorPost;
import com.yizhaoqi.smartpai.model.creator.CreatorSnapshot;
import com.yizhaoqi.smartpai.model.creator.CustomFieldDefinition;
import com.yizhaoqi.smartpai.repository.creator.CreatorRepository;
import com.yizhaoqi.smartpai.service.agent.AgentUserResolver;
import com.yizhaoqi.smartpai.service.creator.CreatorExportService;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.creator.CustomFieldService;
import com.yizhaoqi.smartpai.service.xhs.XhsRefreshService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 博主数据库 REST 入口。
 * 所有写操作按 ownerOrgTag 做租户隔离，userId 记在 created_by 里。
 */
@RestController
@RequestMapping("/api/v1/creators")
public class CreatorController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final CreatorService creatorService;
    private final CreatorRepository creatorRepository;
    private final CreatorExportService exportService;
    private final CustomFieldService customFieldService;
    private final AgentUserResolver userResolver;
    private final JwtUtils jwtUtils;
    private final ObjectMapper mapper;
    private final XhsRefreshService xhsRefreshService;

    public CreatorController(CreatorService creatorService,
                             CreatorRepository creatorRepository,
                             CreatorExportService exportService,
                             CustomFieldService customFieldService,
                             AgentUserResolver userResolver,
                             JwtUtils jwtUtils,
                             ObjectMapper mapper,
                             XhsRefreshService xhsRefreshService) {
        this.creatorService = creatorService;
        this.creatorRepository = creatorRepository;
        this.exportService = exportService;
        this.customFieldService = customFieldService;
        this.userResolver = userResolver;
        this.jwtUtils = jwtUtils;
        this.mapper = mapper;
        this.xhsRefreshService = xhsRefreshService;
    }

    // ---------- Creator (人) ----------

    @GetMapping
    public ResponseEntity<Object> listCreators(@RequestHeader("Authorization") String auth,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String cooperationStatus,
                                               @RequestParam(required = false) String tagContains,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String sort) {
        User u = resolveUser(auth);
        Pageable pageable = CreatorService.defaultPageable(page, size, sort == null ? "id:desc" : sort);
        Page<Creator> p = creatorService.searchCreators(
                new CreatorService.CreatorSearchQuery(u.getPrimaryOrg(), keyword, cooperationStatus, tagContains),
                pageable);
        return ok(pageToMap(p));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> getCreator(@RequestHeader("Authorization") String auth,
                                             @PathVariable Long id) {
        User u = resolveUser(auth);
        return creatorService.getCreator(id, u.getPrimaryOrg())
                .<ResponseEntity<Object>>map(this::ok)
                .orElseGet(this::notFound);
    }

    @PostMapping
    public ResponseEntity<Object> createCreator(@RequestHeader("Authorization") String auth,
                                                @RequestBody Map<String, Object> body) {
        User u = resolveUser(auth);
        return ok(creatorService.upsertCreator(toCreatorReq(body, null, u)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Object> updateCreator(@RequestHeader("Authorization") String auth,
                                                @PathVariable Long id,
                                                @RequestBody Map<String, Object> body) {
        User u = resolveUser(auth);
        if (creatorService.getCreator(id, u.getPrimaryOrg()).isEmpty()) return notFound();
        return ok(creatorService.upsertCreator(toCreatorReq(body, id, u)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Object> deleteCreator(@RequestHeader("Authorization") String auth,
                                                @PathVariable Long id) {
        User u = resolveUser(auth);
        boolean ok = creatorService.deleteCreator(id, u.getPrimaryOrg());
        return ok ? ok(Map.of("deleted", true)) : notFound();
    }

    // ---------- Account (平台账号) ----------

    @GetMapping("/accounts")
    public ResponseEntity<Object> listAccounts(@RequestHeader("Authorization") String auth,
                                               @RequestParam(required = false) String platform,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String categoryMain,
                                               @RequestParam(required = false) Long followersMin,
                                               @RequestParam(required = false) Long followersMax,
                                               @RequestParam(required = false) Boolean verifiedOnly,
                                               @RequestParam(required = false) Long creatorId,
                                               @RequestParam(required = false) String tagContains,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String sort) {
        User u = resolveUser(auth);
        Pageable pageable = CreatorService.defaultPageable(page, size, sort == null ? "id:desc" : sort);
        Page<CreatorAccount> p = creatorService.searchAccounts(
                new CreatorService.AccountSearchQuery(u.getPrimaryOrg(), platform, keyword, categoryMain,
                        followersMin, followersMax, verifiedOnly, creatorId, tagContains),
                pageable);
        return ok(pageToMap(p));
    }

    @GetMapping("/accounts/{id}")
    public ResponseEntity<Object> getAccount(@RequestHeader("Authorization") String auth,
                                             @PathVariable Long id,
                                             @RequestParam(defaultValue = "true") boolean includePosts) {
        User u = resolveUser(auth);
        return creatorService.getAccount(id, u.getPrimaryOrg())
                .<ResponseEntity<Object>>map(a -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("account", a);
                    if (a.getCreatorId() != null) {
                        creatorRepository.findById(a.getCreatorId())
                                .filter(c -> u.getPrimaryOrg().equals(c.getOwnerOrgTag()))
                                .ifPresent(c -> payload.put("creator", c));
                    }
                    if (includePosts) {
                        List<CreatorPost> posts = creatorService.latestPostsOf(id);
                        payload.put("recentPosts", posts);
                    }
                    return ok(payload);
                })
                .orElseGet(this::notFound);
    }

    @PostMapping("/accounts")
    public ResponseEntity<Object> upsertAccount(@RequestHeader("Authorization") String auth,
                                                @RequestBody Map<String, Object> body) {
        User u = resolveUser(auth);
        return ok(creatorService.upsertAccount(toAccountReq(body, u)));
    }

    @PostMapping("/accounts/{id}/snapshots")
    public ResponseEntity<Object> addSnapshot(@RequestHeader("Authorization") String auth,
                                              @PathVariable Long id,
                                              @RequestBody CreatorSnapshot snapshot) {
        User u = resolveUser(auth);
        return ok(creatorService.addSnapshot(id, u.getPrimaryOrg(), snapshot));
    }

    // ---------- Posts 读取（分页 + TTL 刷新） ----------

    @GetMapping("/accounts/{id}/posts")
    public ResponseEntity<Object> listAccountPosts(@RequestHeader("Authorization") String auth,
                                                   @PathVariable Long id,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @RequestParam(defaultValue = "24") int ttlHours,
                                                   @RequestParam(defaultValue = "false") boolean refresh) {
        User u = resolveUser(auth);
        if (creatorService.getAccount(id, u.getPrimaryOrg()).isEmpty()) return notFound();

        // 主动刷新：调用 xhs refresh service 让数据先拉回来
        if (refresh) {
            xhsRefreshService.refreshAccount(id, u.getPrimaryOrg(),
                    Math.max(size, 20), false,
                    "posts-refresh-" + u.getId() + "-" + id, null);
        }

        Pageable pg = CreatorService.defaultPageable(page, size, "publishedAt:desc");
        Page<CreatorPost> p = creatorService.pagedPostsOf(id, pg);
        CreatorService.CachedPostsResult freshness = creatorService.readPostsWithFreshness(
                id, size, java.time.Duration.ofHours(Math.max(1, ttlHours)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", p.getTotalElements());
        payload.put("page", p.getNumber());
        payload.put("size", p.getSize());
        payload.put("items", p.getContent());
        payload.put("mostRecentSnapshotAt", freshness.mostRecentSnapshotAt());
        payload.put("stale", freshness.stale());
        return ok(payload);
    }

    // ---------- Posts 批量上传 ----------

    @PostMapping("/accounts/{id}/posts:batch")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> batchUpsertPosts(@RequestHeader("Authorization") String auth,
                                                   @PathVariable Long id,
                                                   @RequestBody Map<String, Object> body) {
        User u = resolveUser(auth);
        Object raw = body.get("posts");
        if (!(raw instanceof List<?>)) return bad("posts 必须是数组");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            if (o instanceof Map) rows.add((Map<String, Object>) o);
        }
        CreatorService.PostBatchResult r = creatorService.upsertPosts(id, u.getPrimaryOrg(), rows);
        return ok(Map.of("inserted", r.inserted(), "updated", r.updated(), "skipped", r.skipped()));
    }

    // ---------- XHS 刷新（一键触发 skill） ----------

    @PostMapping("/accounts/{id}/refresh:xhs")
    public ResponseEntity<Object> refreshXhsAccount(@RequestHeader("Authorization") String auth,
                                                    @PathVariable Long id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        User u = resolveUser(auth);
        int limit = 20;
        boolean dryRun = false;
        if (body != null) {
            Object l = body.get("limit");
            if (l instanceof Number n) limit = n.intValue();
            else if (l != null) {
                try { limit = Integer.parseInt(String.valueOf(l)); } catch (Exception ignored) {}
            }
            dryRun = Boolean.TRUE.equals(body.get("dryRun"));
        }
        XhsRefreshService.Result r = xhsRefreshService.refreshAccount(
                id, u.getPrimaryOrg(), limit, dryRun,
                "admin-" + u.getId() + "-" + id, null);
        if (!r.ok()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("errorType", r.errorType());
            err.put("message", r.errorMessage());
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", r.errorMessage(), "data", err));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", r.accountId());
        out.put("fetched", r.fetched());
        if (r.dryRun()) {
            out.put("dryRun", true);
            out.put("preview", r.preview());
        } else {
            out.put("inserted", r.inserted());
            out.put("updated", r.updated());
            out.put("skipped", r.skipped());
        }
        return ok(out);
    }

    // ---------- Custom field definitions ----------

    @GetMapping("/custom-fields")
    public ResponseEntity<Object> listCustomFields(@RequestHeader("Authorization") String auth,
                                                   @RequestParam(defaultValue = "account") String entityType) {
        User u = resolveUser(auth);
        return ok(customFieldService.list(u.getPrimaryOrg(), entityType));
    }

    @PostMapping("/custom-fields")
    public ResponseEntity<Object> upsertCustomField(@RequestHeader("Authorization") String auth,
                                                    @RequestBody CustomFieldDefinition body) {
        User u = resolveUser(auth);
        body.setOwnerOrgTag(u.getPrimaryOrg());
        return ok(customFieldService.upsert(body));
    }

    @DeleteMapping("/custom-fields/{id}")
    public ResponseEntity<Object> deleteCustomField(@RequestHeader("Authorization") String auth,
                                                    @PathVariable Long id) {
        User u = resolveUser(auth);
        return customFieldService.delete(id, u.getPrimaryOrg())
                ? ok(Map.of("deleted", true)) : notFound();
    }

    // ---------- xlsx export ----------

    @GetMapping(value = "/export.xlsx")
    public void exportXlsx(@RequestHeader("Authorization") String auth,
                           @RequestParam(required = false) String platform,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String categoryMain,
                           @RequestParam(required = false) Long followersMin,
                           @RequestParam(required = false) Long followersMax,
                           @RequestParam(required = false) Boolean verifiedOnly,
                           @RequestParam(required = false) Long creatorId,
                           @RequestParam(required = false) String tagContains,
                           @RequestParam(defaultValue = "2000") int maxRows,
                           @RequestParam(required = false) List<String> fields,
                           @RequestParam(defaultValue = "true") boolean includeCustomFields,
                           @RequestParam(required = false) List<Long> accountIds,
                           HttpServletResponse resp) throws Exception {
        User u = resolveUser(auth);
        int cap = Math.max(1, Math.min(maxRows, 10000));

        List<CreatorAccount> accounts = new ArrayList<>();
        // 分支 1：客户端勾了一批 id（多选导出） → 直接按 id 捞
        if (accountIds != null && !accountIds.isEmpty()) {
            List<Long> safeIds = accountIds.stream().distinct().limit(cap).toList();
            for (CreatorAccount a : creatorService.getAccountsByIds(safeIds)) {
                if (u.getPrimaryOrg().equals(a.getOwnerOrgTag())) accounts.add(a);
            }
        } else {
            // 分支 2：按筛选条件全量导出（或封顶 maxRows）
            CreatorService.AccountSearchQuery q = new CreatorService.AccountSearchQuery(
                    u.getPrimaryOrg(), platform, keyword, categoryMain,
                    followersMin, followersMax, verifiedOnly, creatorId, tagContains);
            int pageIdx = 0;
            while (accounts.size() < cap) {
                Pageable pg = CreatorService.defaultPageable(pageIdx, 200, "id:desc");
                Page<CreatorAccount> p = creatorService.searchAccounts(q, pg);
                accounts.addAll(p.getContent());
                if (!p.hasNext()) break;
                pageIdx++;
            }
            if (accounts.size() > cap) accounts = accounts.subList(0, cap);
        }

        Map<Long, Creator> creatorMap = new HashMap<>();
        for (CreatorAccount a : accounts) {
            Long cid = a.getCreatorId();
            if (cid != null && !creatorMap.containsKey(cid)) {
                creatorRepository.findById(cid)
                        .filter(c -> u.getPrimaryOrg().equals(c.getOwnerOrgTag()))
                        .ifPresent(c -> creatorMap.put(cid, c));
            }
        }
        List<CreatorExportService.ExportRow> rows = new ArrayList<>(accounts.size());
        for (CreatorAccount a : accounts) {
            rows.add(new CreatorExportService.ExportRow(a, creatorMap.get(a.getCreatorId())));
        }
        List<CustomFieldDefinition> creatorCfs = includeCustomFields
                ? customFieldService.list(u.getPrimaryOrg(), "creator") : List.of();
        List<CustomFieldDefinition> accountCfs = includeCustomFields
                ? customFieldService.list(u.getPrimaryOrg(), "account") : List.of();

        String filename = "creators-" + LocalDateTime.now().format(TS) + ".xlsx";
        resp.setContentType(
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .toString());
        resp.setHeader("Content-Disposition",
                "attachment; filename=\"" + filename + "\"; filename*=UTF-8''"
                        + URLEncoder.encode(filename, StandardCharsets.UTF_8));
        exportService.writeXlsx(resp.getOutputStream(), rows, fields, creatorCfs, accountCfs);
    }

    // ---------- helpers ----------

    private User resolveUser(String auth) {
        String token = auth == null ? "" : auth.replace("Bearer ", "");
        String userId = jwtUtils.extractUserIdFromToken(token);
        if (userId == null) throw new IllegalArgumentException("无效 token");
        return userResolver.resolve(userId);
    }

    private ResponseEntity<Object> ok(Object data) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", data));
    }

    private ResponseEntity<Object> notFound() {
        return ResponseEntity.status(404).body(Map.of("code", 404, "message", "not found"));
    }

    private ResponseEntity<Object> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", msg));
    }

    private Map<String, Object> pageToMap(Page<?> p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", p.getTotalElements());
        m.put("page", p.getNumber());
        m.put("size", p.getSize());
        m.put("items", p.getContent());
        return m;
    }

    private CreatorService.CreatorUpsertRequest toCreatorReq(Map<String, Object> body, Long id, User u) {
        return new CreatorService.CreatorUpsertRequest(
                id,
                u.getPrimaryOrg(),
                str(body, "displayName"),
                str(body, "realName"),
                str(body, "gender"),
                asInt(body, "birthYear"),
                str(body, "city"),
                str(body, "country"),
                jsonish(body, "personaTags"),
                jsonish(body, "trackTags"),
                str(body, "cooperationStatus"),
                asLong(body, "internalOwnerId"),
                str(body, "internalNotes"),
                str(body, "priceNote"),
                jsonish(body, "customFields"),
                String.valueOf(u.getId()));
    }

    private CreatorService.CreatorAccountUpsertRequest toAccountReq(Map<String, Object> body, User u) {
        return new CreatorService.CreatorAccountUpsertRequest(
                asLong(body, "creatorId"),
                u.getPrimaryOrg(),
                str(body, "platform"),
                str(body, "platformUserId"),
                str(body, "handle"),
                str(body, "displayName"),
                str(body, "avatarUrl"),
                str(body, "bio"),
                asLong(body, "followers"),
                asLong(body, "following"),
                asLong(body, "likes"),
                asLong(body, "posts"),
                asLong(body, "avgLikes"),
                asLong(body, "avgComments"),
                asDouble(body, "hitRatio"),
                asDouble(body, "engagementRate"),
                body.get("verified") instanceof Boolean b ? b : null,
                str(body, "verifyType"),
                str(body, "region"),
                str(body, "homepageUrl"),
                str(body, "categoryMain"),
                str(body, "categorySub"),
                jsonish(body, "platformTags"),
                jsonish(body, "customFields")
        );
    }

    private String jsonish(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof String s) return s;
        try { return mapper.writeValueAsString(v); } catch (Exception e) { return null; }
    }
    private String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : String.valueOf(v); }
    private Integer asInt(Map<String, Object> m, String k) {
        Object v = m.get(k); if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return null; }
    }
    private Long asLong(Map<String, Object> m, String k) {
        Object v = m.get(k); if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }
    private Double asDouble(Map<String, Object> m, String k) {
        Object v = m.get(k); if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
