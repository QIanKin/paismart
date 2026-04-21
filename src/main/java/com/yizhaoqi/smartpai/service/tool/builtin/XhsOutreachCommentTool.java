package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.creator.OutreachRecord;
import com.yizhaoqi.smartpai.repository.creator.OutreachRecordRepository;
import com.yizhaoqi.smartpai.service.creator.CreatorScreenService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.BrowserSkillRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * xhs_outreach_comment：批量外联评论触达。
 *
 * <p>流程：
 * <ol>
 *   <li>调 skill {@code xhs-outreach-comment}（浏览器自动化）</li>
 *   <li>把 skill 返回的每条 result 补 contact 抽取、写入 {@code outreach_records}</li>
 *   <li>聚合统计给 agent</li>
 * </ol>
 */
@Component
public class XhsOutreachCommentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(XhsOutreachCommentTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BrowserSkillRunner runner;
    private final OutreachRecordRepository outreachRepo;
    private final CreatorScreenService screenService;
    private final JsonNode schema;

    public XhsOutreachCommentTool(BrowserSkillRunner runner,
                                  OutreachRecordRepository outreachRepo,
                                  CreatorScreenService screenService) {
        this.runner = runner;
        this.outreachRepo = outreachRepo;
        this.screenService = screenService;
        var targetItem = ToolInputSchemas.mapper().createObjectNode();
        targetItem.put("type", "object");
        var tprops = targetItem.putObject("properties");
        tprops.putObject("query").put("type", "string").put("description", "搜索词（当没有 profileUrl 时用）");
        tprops.putObject("profileUrl").put("type", "string").put("description", "小红书主页完整 URL");
        tprops.putObject("platformUserId").put("type", "string");
        tprops.putObject("nickname").put("type", "string");
        this.schema = ToolInputSchemas.object()
                .arrayProp("targets",
                        "博主列表，每项含 query 或 profileUrl 任一。字段：query/profileUrl/platformUserId/nickname",
                        targetItem, true)
                .stringProp("commentText",
                        "评论正文，支持 {nickname}/{platformUserId} 占位符", true)
                .integerProp("maxTargets", "最大触达数（默认 20，上限 100）", false)
                .integerProp("delayMs", "两次触达间隔毫秒（默认 15000）", false)
                .booleanProp("enableScreening", "是否本地筛商业号（默认 true）", false)
                .booleanProp("stopOnRateLimit", "命中反爬立刻停（默认 true）", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_outreach_comment"; }

    @Override public String description() {
        return "通过业务员本机浏览器 CDP 批量给小红书博主的第一条笔记发评论，"
                + "自动跳过商业号、写入外联台账。风险操作，需要业务员确认。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return false; }

    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("当前上下文缺少 orgTag");

        JsonNode targets = input.get("targets");
        if (targets == null || !targets.isArray() || targets.size() == 0) {
            return ToolResult.error("targets 必填且为非空数组");
        }
        String commentText = input.path("commentText").asText("");
        if (commentText.isBlank()) return ToolResult.error("commentText 必填");
        int maxTargets = Math.min(input.path("maxTargets").asInt(20), 100);
        int delayMs = Math.max(1000, input.path("delayMs").asInt(15000));
        boolean enableScreening = !input.hasNonNull("enableScreening") || input.get("enableScreening").asBoolean(true);
        boolean stopOnRateLimit = !input.hasNonNull("stopOnRateLimit") || input.get("stopOnRateLimit").asBoolean(true);

        BrowserSkillRunner.RunRequest req = new BrowserSkillRunner.RunRequest();
        req.orgTag = orgTag;
        req.sessionId = ctx.sessionId();
        req.skillName = "xhs-outreach-comment";
        req.scriptRelative = "scripts/batch_comment.mjs";
        req.extraArgs.add("--targets");
        req.extraArgs.add(targets.toString());
        req.extraArgs.add("--comment-text");
        req.extraArgs.add(commentText);
        req.extraArgs.add("--max-targets");
        req.extraArgs.add(String.valueOf(maxTargets));
        req.extraArgs.add("--delay-ms");
        req.extraArgs.add(String.valueOf(delayMs));
        req.extraArgs.add("--enable-screening");
        req.extraArgs.add(String.valueOf(enableScreening));
        req.extraArgs.add("--stop-on-rate-limit");
        req.extraArgs.add(String.valueOf(stopOnRateLimit));
        // 评论节奏 delayMs * maxTargets + 预热
        req.timeoutSeconds = Math.min(7200, 60 + (delayMs / 1000 + 30) * maxTargets);
        req.cancelled = ctx.cancelled();

        BrowserSkillRunner.RunResult res = runner.run(req);
        if (!res.ok()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("errorType", res.errorType());
            return ToolResult.error("xhs-outreach-comment 执行失败: " + res.errorMessage(), detail);
        }

        // 把每条 result 写入 outreach_records
        JsonNode payload = res.payload();
        JsonNode resultsNode = payload.path("results");
        int persisted = 0;
        List<Map<String, Object>> digest = new ArrayList<>();
        if (resultsNode.isArray()) {
            for (JsonNode r : resultsNode) {
                try {
                    OutreachRecord rec = toRecord(r, orgTag, ctx);
                    outreachRepo.save(rec);
                    persisted++;
                    digest.add(summarizeResult(r, rec.getId()));
                } catch (Exception e) {
                    log.warn("写入 OutreachRecord 失败: {}", e.getMessage());
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", MAPPER.convertValue(payload.path("summary"), Map.class));
        data.put("persistedCount", persisted);
        data.put("results", digest);

        String summaryStr = payload.path("summary").toString();
        return ToolResult.of(data,
                "外联完成: " + summaryStr + " 写入 outreach_records=" + persisted);
    }

    private OutreachRecord toRecord(JsonNode r, String orgTag, ToolContext ctx) {
        OutreachRecord rec = new OutreachRecord();
        rec.setOwnerOrgTag(orgTag);
        rec.setPlatform("xhs");
        rec.setPlatformUserId(text(r, "platformUserId"));
        rec.setNickname(text(r, "nickname"));
        rec.setProfileUrl(text(r, "profileUrl"));
        rec.setPostId(text(r, "postId"));
        rec.setPostUrl(text(r, "postUrl"));
        rec.setAction(OutreachRecord.Action.COMMENT);
        rec.setMessageText(text(r, "messageText"));
        rec.setVerdict(text(r, "verdict"));

        // 补联系方式（skill 侧没抽，交给 Java）
        String bio = text(r, "bio");
        CreatorScreenService.ContactInfo contact = screenService.extractContacts(bio);
        if (!contact.email().isEmpty()) rec.setEmail(contact.email());
        if (!contact.wechat().isEmpty()) rec.setWechat(contact.wechat());
        if (!contact.phone().isEmpty()) rec.setPhone(contact.phone());

        JsonNode cm = r.path("commercialMatches");
        JsonNode pm = r.path("personalMatches");
        if (cm.isArray()) rec.setCommercialScore(cm.size());
        if (pm.isArray()) rec.setPersonalScore(pm.size());
        if (!cm.isMissingNode() || !pm.isMissingNode()) {
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("commercialMatches", MAPPER.convertValue(cm, List.class));
            reason.put("personalMatches", MAPPER.convertValue(pm, List.class));
            try {
                rec.setVerdictReasonJson(MAPPER.writeValueAsString(reason));
            } catch (Exception ignore) {}
        }

        rec.setStatus(parseStatus(text(r, "status")));
        rec.setErrorMessage(text(r, "errorMessage"));
        if (r.hasNonNull("commentedAt")) {
            try {
                String iso = r.get("commentedAt").asText();
                rec.setExecutedAt(LocalDateTime.parse(iso.replace("Z", "")));
            } catch (Exception ignore) {
                rec.setExecutedAt(LocalDateTime.now());
            }
        }
        rec.setExecutedBy(ctx.userId());
        rec.setExecutorSkill("xhs-outreach-comment");
        rec.setSessionId(ctx.sessionId());
        rec.setProjectId(ctx.projectId());
        return rec;
    }

    private OutreachRecord.Status parseStatus(String s) {
        if (s == null || s.isBlank()) return OutreachRecord.Status.PENDING;
        try {
            return OutreachRecord.Status.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return OutreachRecord.Status.FAILED;
        }
    }

    private Map<String, Object> summarizeResult(JsonNode r, Long recordId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outreachRecordId", recordId);
        m.put("platformUserId", text(r, "platformUserId"));
        m.put("nickname", text(r, "nickname"));
        m.put("status", text(r, "status"));
        m.put("verdict", text(r, "verdict"));
        m.put("postUrl", text(r, "postUrl"));
        return m;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
