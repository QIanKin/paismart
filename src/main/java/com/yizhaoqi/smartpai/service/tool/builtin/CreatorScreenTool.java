package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.creator.CreatorScreenService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * creator_screen：给一段博主文本（bio / 昵称 / 主页抓取）打分判断真人 vs 商业号，
 * 并抽取联系方式（email / wechat / phone）。
 *
 * <p>典型用法：agent 先用 xhs-user-notes / xhs-search-notes 拿到博主 bio/pageText，
 * 再调本 tool 决定是否值得发起外联；然后把 verdict/contactInfo 作为下一步的参数
 * （如写入 OutreachRecord / 选择不同的私信话术）。
 */
@Component
public class CreatorScreenTool implements Tool {

    private final CreatorScreenService screenService;
    private final JsonNode schema;

    public CreatorScreenTool(CreatorScreenService screenService) {
        this.screenService = screenService;
        this.schema = ToolInputSchemas.object()
                .stringProp("nickname", "博主昵称（可空）", false)
                .stringProp("bio", "博主简介 / 个人签名（可空）", false)
                .stringProp("pageText", "主页纯文本抓取（可选，有则精度更高）", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "creator_screen"; }

    @Override public String description() {
        return "博主筛选器：基于商业/个人双词库判定一个博主是真人账号还是商业号，"
                + "同时从文本里抽 email / 微信号 / 手机号。用于外联前置决策。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String nickname = input.path("nickname").asText("");
        String bio = input.path("bio").asText("");
        String pageText = input.path("pageText").asText("");
        if (bio.isBlank() && pageText.isBlank() && nickname.isBlank()) {
            return ToolResult.error("nickname / bio / pageText 至少提供一项");
        }

        CreatorScreenService.ScreenResult res = screenService.screen(bio, nickname, pageText);
        // 从 bio + pageText 合并后抽联系方式
        String combined = (bio == null ? "" : bio) + " " + (pageText == null ? "" : pageText);
        CreatorScreenService.ContactInfo contact = screenService.extractContacts(combined);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("verdict", res.verdict());
        data.put("commercialScore", res.commercialScore());
        data.put("personalScore", res.personalScore());
        data.put("commercialMatches", res.commercialMatches());
        data.put("personalMatches", res.personalMatches());

        Map<String, Object> contactMap = new LinkedHashMap<>();
        contactMap.put("email", contact.email());
        contactMap.put("wechat", contact.wechat());
        contactMap.put("phone", contact.phone());
        contactMap.put("hasAny", screenService.hasAnyContact(contact));
        data.put("contactInfo", contactMap);

        String summary = String.format("verdict=%s (C=%d P=%d) contact=%s",
                res.verdict(), res.commercialScore(), res.personalScore(),
                screenService.hasAnyContact(contact) ? "yes" : "no");
        return ToolResult.of(data, summary);
    }
}
