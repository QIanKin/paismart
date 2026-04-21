package com.yizhaoqi.smartpai.service.xhs;

import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.repository.xhs.XhsCookieRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XhsCookieService.bulkUpsertFromLogin 专项单测。
 *
 * 用轻量内存 stub 替代真实 JPA repository + 真实 CookieCipher（构造器无参就能创建，
 * 用默认密钥走 AES/GCM），避免起 Spring context；跑得够快，每 CI 都可以全量跑。
 */
class XhsCookieServiceBulkUpsertTest {

    private XhsCookieService service;
    private InMemoryXhsCookieRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryXhsCookieRepository();
        // 用默认 secret；CookieCipher 会打一个 WARN 但功能正常
        service = new XhsCookieService(repo, new CookieCipher(""));
    }

    // ---------- happy path ----------

    @Test
    void createsNewRowsForEachPlatform() {
        List<XhsCookieService.PlatformCookie> items = List.of(
                new XhsCookieService.PlatformCookie("xhs_pc",      "a1=pc_a; web_session=pc_s; webId=pc_w"),
                new XhsCookieService.PlatformCookie("xhs_creator", "a1=cr_a; web_session=cr_s; webId=cr_w"),
                new XhsCookieService.PlatformCookie("xhs_pgy",     "a1=pg_a; web_session=pg_s; webId=pg_w"),
                new XhsCookieService.PlatformCookie("xhs_qianfan", "a1=qf_a; web_session=qf_s; webId=qf_w")
        );

        XhsCookieService.BulkUpsertResult res = service.bulkUpsertFromLogin(
                "org1", "sess-1", "label1", "admin", items);

        assertEquals(4, res.createdIds().size(), "四个平台全 new");
        assertTrue(res.updatedIds().isEmpty());
        assertTrue(res.skipped().isEmpty());
        assertEquals(4, repo.store.size());

        XhsCookie any = repo.store.values().iterator().next();
        assertEquals("org1", any.getOwnerOrgTag());
        assertEquals("label1", any.getAccountLabel());
        assertEquals(XhsCookie.Status.ACTIVE, any.getStatus());
        assertEquals(XhsCookie.Source.QR_LOGIN, any.getSource());
        assertEquals("sess-1", any.getLoginSessionId());
        assertEquals(0, any.getFailCount());
        assertNotNull(any.getCookieEncrypted(), "必须落密文");
        assertTrue(any.getCookieKeys().contains("a1"), "key 列表里要有 a1");
    }

    // ---------- 幂等覆盖：同 label 再跑一次应 update 而非 create ----------

    @Test
    void secondRunWithSameLabelUpdatesExistingRows() {
        List<XhsCookieService.PlatformCookie> first = List.of(
                new XhsCookieService.PlatformCookie("xhs_pc", "a1=1; web_session=1; webId=1")
        );
        XhsCookieService.BulkUpsertResult r1 = service.bulkUpsertFromLogin("org1", "sess-1", "same-label", "u", first);
        assertEquals(1, r1.createdIds().size());

        List<XhsCookieService.PlatformCookie> second = List.of(
                new XhsCookieService.PlatformCookie("xhs_pc", "a1=2; web_session=2; webId=2; gid=g")
        );
        XhsCookieService.BulkUpsertResult r2 = service.bulkUpsertFromLogin("org1", "sess-2", "same-label", "u", second);
        assertTrue(r2.createdIds().isEmpty());
        assertEquals(1, r2.updatedIds().size(), "同 label 应覆盖");
        assertEquals(1, repo.store.size(), "DB 里还是 1 条");

        XhsCookie row = repo.store.values().iterator().next();
        assertEquals("sess-2", row.getLoginSessionId(), "loginSessionId 要更新到最新一次");
        assertTrue(row.getCookieKeys().contains("gid"), "新 key 应反映出来");
    }

    // ---------- 缺字段 → skipped ----------

    @Test
    void missingRequiredFieldsAreSkippedForXhsWebPlatforms() {
        List<XhsCookieService.PlatformCookie> items = List.of(
                new XhsCookieService.PlatformCookie("xhs_pc", "a1=ok; webId=ok"), // 缺 web_session
                new XhsCookieService.PlatformCookie("xhs_creator", "a1=a; web_session=s; webId=w")
        );
        XhsCookieService.BulkUpsertResult res = service.bulkUpsertFromLogin(
                "org1", "sess-x", null, "u", items);
        assertEquals(1, res.createdIds().size(), "xhs_creator OK");
        assertEquals(1, res.skipped().size(), "xhs_pc 被跳");
        String reason = res.skipped().get(0);
        assertTrue(reason.startsWith("xhs_pc:"));
        assertTrue(reason.contains("web_session"));
    }

    // ---------- 空输入 ----------

    @Test
    void emptyInputIsNoOp() {
        XhsCookieService.BulkUpsertResult r = service.bulkUpsertFromLogin(
                "org1", "sess", "l", "u", List.of());
        assertTrue(r.createdIds().isEmpty());
        assertTrue(r.updatedIds().isEmpty());
        assertTrue(r.skipped().isEmpty());
    }

    // ---------- 非 xhs-web 平台不做必填校验 ----------

    @Test
    void nonWebCookiePlatformsSkipRequiredCheck() {
        // xhs_spotlight 是 OAuth token，不要求 a1/web_session
        List<XhsCookieService.PlatformCookie> items = List.of(
                new XhsCookieService.PlatformCookie("xhs_spotlight", "access_token=abc; expires_in=3600")
        );
        XhsCookieService.BulkUpsertResult r = service.bulkUpsertFromLogin(
                "org1", "sess", "l", "u", items);
        assertEquals(1, r.createdIds().size(), "spotlight 不看 a1/web_session，应直接入库");
        assertTrue(r.skipped().isEmpty());
    }

    // ================================================================
    // In-memory repo：只实现服务实际调用的方法；其他方法抛 UnsupportedOperationException
    // ================================================================
    private static final class InMemoryXhsCookieRepository implements XhsCookieRepository {
        private final Map<Long, XhsCookie> store = new ConcurrentHashMap<>();
        private final AtomicLong idSeq = new AtomicLong(0);

        @Override
        public List<XhsCookie> findByOwnerOrgTagOrderByIdDesc(String ownerOrgTag) {
            return store.values().stream()
                    .filter(c -> ownerOrgTag.equals(c.getOwnerOrgTag()))
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .toList();
        }

        @Override
        public List<XhsCookie> findByOwnerOrgTagAndPlatformAndStatus(
                String ownerOrgTag, String platform, XhsCookie.Status status) {
            return store.values().stream()
                    .filter(c -> ownerOrgTag.equals(c.getOwnerOrgTag())
                            && platform.equals(c.getPlatform())
                            && status == c.getStatus())
                    .toList();
        }

        @Override
        public Optional<XhsCookie> findFirstByOwnerOrgTagAndPlatformAndAccountLabel(
                String ownerOrgTag, String platform, String accountLabel) {
            return store.values().stream()
                    .filter(c -> ownerOrgTag.equals(c.getOwnerOrgTag())
                            && platform.equals(c.getPlatform())
                            && accountLabel.equals(c.getAccountLabel()))
                    .findFirst();
        }

        @Override
        public <S extends XhsCookie> S save(S entity) {
            if (entity.getId() == null) entity.setId(idSeq.incrementAndGet());
            store.put(entity.getId(), entity);
            return entity;
        }

        @Override
        public Optional<XhsCookie> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsById(Long id) { return store.containsKey(id); }

        @Override
        public void delete(XhsCookie entity) { store.remove(entity.getId()); }

        // ------- 下方方法在本测试不会触发；给空实现防止编译错 -------
        @Override public List<XhsCookie> findAll() { return List.copyOf(store.values()); }
        @Override public List<XhsCookie> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
        @Override public org.springframework.data.domain.Page<XhsCookie> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public List<XhsCookie> findAllById(Iterable<Long> longs) {
            List<XhsCookie> out = new java.util.ArrayList<>();
            longs.forEach(i -> { XhsCookie c = store.get(i); if (c != null) out.add(c); });
            return out;
        }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(Long id) { store.remove(id); }
        @Override public void deleteAll(Iterable<? extends XhsCookie> entities) { entities.forEach(this::delete); }
        @Override public void deleteAll() { store.clear(); }
        @Override public void deleteAllById(Iterable<? extends Long> longs) { longs.forEach(this::deleteById); }
        @Override public void deleteAllInBatch() { store.clear(); }
        @Override public void deleteAllInBatch(Iterable<XhsCookie> entities) { entities.forEach(this::delete); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> longs) { longs.forEach(this::deleteById); }
        @Override public <S extends XhsCookie> List<S> saveAll(Iterable<S> entities) {
            List<S> out = new java.util.ArrayList<>();
            entities.forEach(e -> out.add(save(e)));
            return out;
        }
        @Override public XhsCookie getById(Long id) { return store.get(id); }
        @Override public XhsCookie getOne(Long id) { return store.get(id); }
        @Override public XhsCookie getReferenceById(Long id) { return store.get(id); }
        @Override public <S extends XhsCookie> List<S> saveAllAndFlush(Iterable<S> entities) { return saveAll(entities); }
        @Override public <S extends XhsCookie> S saveAndFlush(S entity) { return save(entity); }
        @Override public void flush() {}
        @Override public <S extends XhsCookie> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends XhsCookie> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends XhsCookie> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends XhsCookie> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends XhsCookie> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends XhsCookie> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends XhsCookie, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }

        // 防 IDE 抱怨 "unused private field"
        @SuppressWarnings("unused")
        boolean hasAny() { return !store.isEmpty(); }

        // helper for assertions
        @SuppressWarnings("unused")
        boolean isEmptyStore() { assertFalse(store.isEmpty()); return true; }
    }
}
