package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.config.TikhubProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用 com.sun.net.httpserver.HttpServer 起一个本地 mock，覆盖 TikHub 关键路径：
 *  - extract_share_info：从分享 URL 抠 note_id + xsec_token
 *  - web_v3/fetch_note_detail：解析 noteCard → NoteDetail
 *  - HTTP 4xx 错误归一化（401/404/429）
 *  - pickStream 清晰度选择
 *  - downloadStreamTo CDN fallback
 */
class TikhubXhsServiceTest {

    private HttpServer server;
    private TikhubXhsService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Integer> hits = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());

        // 默认 200 / 各 path 在用例里 set。
        server.createContext("/api/v1/xiaohongshu/app/extract_share_info", ex -> {
            hits.merge("extract", 1, Integer::sum);
            String body = "{\"code\":200,\"data\":{\"note_id\":\"abc123\",\"xsec_token\":\"TKN42\"}}";
            sendJson(ex, 200, body);
        });
        server.createContext("/api/v1/xiaohongshu/web_v3/fetch_note_detail", ex -> {
            hits.merge("detail", 1, Integer::sum);
            String body = """
                    {
                      "code": 200,
                      "data": {
                        "data": {
                          "items": [{
                            "noteCard": {
                              "noteId": "abc123",
                              "title": "TestTitle",
                              "desc": "Hello",
                              "type": "video",
                              "ipLocation": "上海",
                              "time": 1700000000000,
                              "lastUpdateTime": 1700000999999,
                              "user": {"userId":"u1","nickname":"PaiSmart","avatar":"https://x"},
                              "interactInfo": {"likedCount":"123","collectedCount":"45","commentCount":"6","shareCount":"7"},
                              "video": {
                                "media": {
                                  "video": {"duration": 42},
                                  "stream": {
                                    "h264": [
                                      {"qualityType":"HD","width":1080,"height":1920,"size":3000000,
                                       "duration":42000,"avgBitrate":900,"fps":30,
                                       "masterUrl":"http://127.0.0.1:%d/cdn-bad","backupUrls":["http://127.0.0.1:%d/cdn-good"]},
                                      {"qualityType":"LD","width":540,"height":960,"size":1500000,"masterUrl":"http://127.0.0.1:%d/cdn-good","backupUrls":[]}
                                    ],
                                    "h265": []
                                  }
                                }
                              },
                              "imageList":[{"urlDefault":"https://cover/a.jpg"}]
                            }
                          }]
                        }
                      }
                    }
                    """.formatted(server.getAddress().getPort(), server.getAddress().getPort(), server.getAddress().getPort());
            sendJson(ex, 200, body);
        });
        server.createContext("/api/v1/xiaohongshu/web_v3/fetch_search_users", ex -> {
            hits.merge("search_users", 1, Integer::sum);
            String body = """
                    {
                      "code": 200,
                      "data": {
                        "data": {
                          "hasMore": true,
                          "search_id": "sid-1",
                          "users": [
                            {
                              "id": "5ab4695811be106505c1711a",
                              "redId": "945087305",
                              "name": "严大小姐",
                              "fans": "1.5万",
                              "noteCount": 648,
                              "xsecToken": "XT-1",
                              "image": "https://avatar/u1.jpg",
                              "link": "xhsdiscover://1/user/user.5ab4695811be106505c1711a"
                            }
                          ]
                        }
                      }
                    }
                    """;
            sendJson(ex, 200, body);
        });
        server.createContext("/api/v1/xiaohongshu/web_v3/fetch_user_info", ex -> {
            hits.merge("user_info", 1, Integer::sum);
            String body = """
                    {
                      "code": 200,
                      "data": {
                        "data": {
                          "basicInfo": {
                            "userid": "5ab4695811be106505c1711a",
                            "nickname": "严大小姐",
                            "redId": "945087305",
                            "imageb": "https://avatar/u1-large.jpg",
                            "desc": "测试简介",
                            "ipLocation": "上海"
                          },
                          "interactions": {
                            "fans": "1.5万",
                            "liked": "28.6万",
                            "follows": "123",
                            "notes": "648"
                          },
                          "redOfficialVerifyType": 0,
                          "showRedOfficialVerifyIcon": false
                        }
                      }
                    }
                    """;
            sendJson(ex, 200, body);
        });
        server.createContext("/api/v1/xiaohongshu/web_v3/fetch_user_notes", ex -> {
            hits.merge("user_notes", 1, Integer::sum);
            String body = """
                    {
                      "code": 200,
                      "data": {
                        "data": {
                          "cursor": "CURSOR-2",
                          "hasMore": true,
                          "notes": [
                            {
                              "noteId": "note-1",
                              "cursor": "note-1",
                              "xsecToken": "TKN-N1",
                              "type": "video",
                              "displayTitle": "笔记标题1",
                              "cover": {
                                "urlDefault": "https://cover/n1.jpg"
                              },
                              "interactInfo": {
                                "likedCount": "100",
                                "commentCount": "20",
                                "shareCount": "5",
                                "collectedCount": "8"
                              },
                              "user": {
                                "userId": "5ab4695811be106505c1711a",
                                "nickname": "严大小姐"
                              }
                            }
                          ]
                        }
                      }
                    }
                    """;
            sendJson(ex, 200, body);
        });
        server.createContext("/api/v1/xiaohongshu/web_v3/fetch_search_notes", ex -> {
            hits.merge("search_notes", 1, Integer::sum);
            String body = """
                    {
                      "code": 200,
                      "data": {
                        "data": {
                          "hasMore": false,
                          "search_id": "note-search-1",
                          "items": [
                            {
                              "id": "note-2",
                              "xsecToken": "TKN-N2",
                              "title": "搜索笔记",
                              "type": "normal",
                              "cover": {
                                "urlDefault": "https://cover/n2.jpg"
                              },
                              "interactInfo": {
                                "likedCount": "88",
                                "commentCount": "9",
                                "shareCount": "3"
                              },
                              "user": {
                                "userId": "u2",
                                "nickname": "测试作者"
                              }
                            }
                          ]
                        }
                      }
                    }
                    """;
            sendJson(ex, 200, body);
        });
        // 模拟 backup 直链能拿到 mp4
        server.createContext("/cdn-good", ex -> {
            byte[] payload = "FAKE-MP4-CONTENT".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "video/mp4");
            ex.sendResponseHeaders(200, payload.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(payload);
            }
        });
        // 模拟 master 失败
        server.createContext("/cdn-bad", ex -> {
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });

        server.start();
        TikhubProperties props = new TikhubProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setApiKey("test-token");
        props.setTimeoutSeconds(5);
        props.setDownloadTimeoutSeconds(5);
        service = new TikhubXhsService(props);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void notConfiguredWhenDisabled() {
        TikhubProperties p = new TikhubProperties();
        p.setEnabled(false);
        TikhubXhsService s = new TikhubXhsService(p);
        assertFalse(s.configured());
    }

    @Test
    void extractShareInfo_returnsNoteIdAndToken() throws Exception {
        TikhubXhsService.ApiResult res = service.extractShareInfo("https://www.xhs.com/share/xxx");
        assertEquals(200, res.status());
        JsonNode data = res.data().path("data");
        assertEquals("abc123", data.path("note_id").asText());
        assertEquals("TKN42", data.path("xsec_token").asText());
        assertEquals(1, hits.getOrDefault("extract", 0));
    }

    @Test
    void resolveIdentity_extractsTokenFromUrl_withoutCallingShareInfo() throws Exception {
        // 真实 xhs noteId 是 24 位小写 hex
        String nid = "abc1234567890def01234567";
        TikhubXhsService.NoteIdentity id = service.resolveIdentity(
                "https://www.xiaohongshu.com/explore/" + nid + "?xsec_token=URL_TKN", null, null);
        assertEquals(nid, id.noteId());
        assertEquals("URL_TKN", id.xsecToken());
        // url 自带 token 时不应去调 extract_share_info
        assertEquals(0, hits.getOrDefault("extract", 0));
    }

    @Test
    void fetchNoteDetail_parsesNoteCard() throws Exception {
        TikhubXhsService.ApiResult res = service.fetchNoteDetail("abc123", "TKN42");
        TikhubXhsService.NoteDetail d = res.note();
        assertNotNull(d);
        assertEquals("abc123", d.noteId);
        assertEquals("TestTitle", d.title);
        assertEquals("PaiSmart", d.authorName);
        assertEquals(42, d.durationSec);
        assertEquals(123, d.likedCount);
        assertEquals(2, d.streams.size());
        assertTrue(d.isVideoNote());
        assertEquals("https://cover/a.jpg", d.coverUrl);
    }

    @Test
    void fetchNoteDetail_requiresXsecToken() {
        TikhubXhsService.ApiException ex = assertThrows(TikhubXhsService.ApiException.class,
                () -> service.fetchNoteDetail("abc123", ""));
        assertEquals("missing_xsec_token", ex.code());
    }

    @Test
    void pickStream_byQualityFiltersByHeight() throws Exception {
        TikhubXhsService.ApiResult res = service.fetchNoteDetail("abc123", "TKN42");
        TikhubXhsService.NoteDetail d = res.note();

        Optional<TikhubXhsService.VideoStream> best = service.pickStream(d, "best");
        assertTrue(best.isPresent());
        assertEquals(1920, best.get().height);

        Optional<TikhubXhsService.VideoStream> p720 = service.pickStream(d, "720p");
        assertTrue(p720.isPresent());
        assertEquals(960, p720.get().height); // 540p ≤ 720p
    }

    @Test
    void downloadStream_fallsBackToBackupUrl() throws Exception {
        TikhubXhsService.ApiResult res = service.fetchNoteDetail("abc123", "TKN42");
        TikhubXhsService.VideoStream s = res.note().streams.get(0); // master 是 cdn-bad，backup cdn-good
        Path target = Files.createTempFile("dl-", ".mp4");
        try {
            long size = service.downloadStreamTo(s, target);
            assertTrue(size > 0);
            assertEquals("FAKE-MP4-CONTENT", Files.readString(target));
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void unauthorizedMappedFromHttp401() throws Exception {
        // 把 extract path 重映射成 401
        server.removeContext("/api/v1/xiaohongshu/app/extract_share_info");
        server.createContext("/api/v1/xiaohongshu/app/extract_share_info", ex -> {
            sendJson(ex, 401, "{\"detail\":{\"code\":401,\"message_zh\":\"无效凭证\"}}");
        });
        TikhubXhsService.ApiException ex = assertThrows(TikhubXhsService.ApiException.class,
                () -> service.extractShareInfo("https://share/x"));
        assertEquals("unauthorized", ex.code());
    }

    @Test
    void noteNotFoundMappedFromHttp404() throws Exception {
        server.removeContext("/api/v1/xiaohongshu/web_v3/fetch_note_detail");
        server.createContext("/api/v1/xiaohongshu/web_v3/fetch_note_detail", ex -> {
            sendJson(ex, 404, "{\"detail\":{\"code\":404,\"message\":\"note not found\"}}");
        });
        TikhubXhsService.ApiException ex = assertThrows(TikhubXhsService.ApiException.class,
                () -> service.fetchNoteDetail("abc123", "TKN42"));
        assertEquals("note_not_found", ex.code());
    }

    @Test
    void rateLimitMappedFromHttp429() throws Exception {
        server.removeContext("/api/v1/xiaohongshu/web_v3/fetch_note_detail");
        server.createContext("/api/v1/xiaohongshu/web_v3/fetch_note_detail", ex -> {
            sendJson(ex, 429, "{\"detail\":{\"code\":429,\"message_zh\":\"限流\"}}");
        });
        TikhubXhsService.ApiException ex = assertThrows(TikhubXhsService.ApiException.class,
                () -> service.fetchNoteDetail("abc123", "TKN42"));
        assertEquals("rate_limited", ex.code());
    }

    @Test
    void searchUsers_returnsMatchedUser() throws Exception {
        TikhubXhsService.UserSearchResult res = service.searchUsers("严大小姐", 1);
        assertEquals(1, res.users.size());
        assertEquals("5ab4695811be106505c1711a", res.users.get(0).userId);
        assertEquals("945087305", res.users.get(0).redId);
        assertEquals("严大小姐", res.users.get(0).nickname);
    }

    @Test
    void fetchUserInfo_parsesProfile() throws Exception {
        TikhubXhsService.UserProfile res = service.fetchUserInfo("5ab4695811be106505c1711a");
        assertEquals("5ab4695811be106505c1711a", res.userId);
        assertEquals("945087305", res.redId);
        assertEquals("严大小姐", res.nickname);
        assertEquals("上海", res.ipLocation);
        assertEquals(648L, res.noteCount);
    }

    @Test
    void fetchUserNotes_parsesList() throws Exception {
        TikhubXhsService.UserNotesResult res = service.fetchUserNotes("5ab4695811be106505c1711a", "", 10);
        assertEquals("CURSOR-2", res.cursor);
        assertEquals(1, res.notes.size());
        assertEquals("note-1", res.notes.get(0).noteId);
        assertEquals("笔记标题1", res.notes.get(0).title);
        assertEquals(100L, res.notes.get(0).likes);
    }

    @Test
    void searchNotes_parsesList() throws Exception {
        TikhubXhsService.SearchNotesResult res = service.searchNotes("测试", 1, "general", 0);
        assertEquals(1, res.notes.size());
        assertEquals("note-2", res.notes.get(0).noteId);
        assertEquals("搜索笔记", res.notes.get(0).title);
        assertEquals("测试作者", res.notes.get(0).userName);
    }

    private static void sendJson(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
