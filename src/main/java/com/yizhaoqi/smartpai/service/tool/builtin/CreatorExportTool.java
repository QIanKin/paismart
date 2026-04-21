package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.repository.creator.CreatorRepository;
import com.yizhaoqi.smartpai.service.creator.CreatorExportService;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.creator.CustomFieldService;
import com.yizhaoqi.smartpai.service.skill.SandboxPathResolver;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * creator_export：把 creator_search 同条件的结果导出成 xlsx，落在沙箱目录，返回相对路径。
 * 通常跟随一个 fs_read 或人工下载。
 */
@Component
public class CreatorExportTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final CreatorService creatorService;
    private final CreatorRepository creatorRepository;
    private final CreatorExportService exportService;
    private final CustomFieldService customFieldService;
    private final SandboxPathResolver paths;
    private final JsonNode schema;

    public CreatorExportTool(CreatorService creatorService,
                             CreatorRepository creatorRepository,
                             CreatorExportService exportService,
                             CustomFieldService customFieldService,
                             SandboxPathResolver paths) {
        this.creatorService = creatorService;
        this.creatorRepository = creatorRepository;
        this.exportService = exportService;
        this.customFieldService = customFieldService;
        this.paths = paths;
        this.schema = ToolInputSchemas.object()
                .stringProp("platform", "平台过滤", false)
                .stringProp("keyword", "关键词", false)
                .stringProp("categoryMain", "主分类", false)
                .integerProp("followersMin", "粉丝下限", false)
                .integerProp("followersMax", "粉丝上限", false)
                .booleanProp("verifiedOnly", "仅认证", false)
                .integerProp("creatorId", "仅导出某 Creator 下账号", false)
                .stringProp("tagContains", "标签模糊", false)
                .integerProp("maxRows", "最大行数（默认 2000，最大 10000）", false)
                .stringProp("fieldsJson", "可选 JSON 字符串数组，列出要导出的内置字段 key；默认全部", false)
                .stringProp("filename", "文件名（不含扩展名），默认 creators-<时间戳>", false)
                .booleanProp("includeCustomFields", "是否加入自定义字段列，默认 true", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "creator_export"; }
    @Override public String description() {
        return "按 creator_search 同样的过滤条件导出 xlsx 到会话沙箱里。返回沙箱相对路径。"
                + "调用前先用 creator_search 确认筛选条件和预期行数。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("当前上下文无 orgTag");

        CreatorService.AccountSearchQuery q = new CreatorService.AccountSearchQuery(
                orgTag,
                text(input, "platform"),
                text(input, "keyword"),
                text(input, "categoryMain"),
                longVal(input, "followersMin"),
                longVal(input, "followersMax"),
                input.hasNonNull("verifiedOnly") ? input.get("verifiedOnly").asBoolean(false) : null,
                longVal(input, "creatorId"),
                text(input, "tagContains"));

        int maxRows = input.has("maxRows") ? input.get("maxRows").asInt(2000) : 2000;
        if (maxRows <= 0) maxRows = 2000;
        if (maxRows > 10000) maxRows = 10000;

        // 先跑第一页取 total，再根据 maxRows 限制收集
        List<CreatorAccount> accounts = new ArrayList<>();
        int pageSize = 200, page = 0;
        long total;
        while (accounts.size() < maxRows) {
            Pageable pg = CreatorService.defaultPageable(page, pageSize, "id:desc");
            Page<CreatorAccount> p = creatorService.searchAccounts(q, pg);
            total = p.getTotalElements();
            accounts.addAll(p.getContent());
            if (!p.hasNext() || accounts.size() >= total) break;
            page++;
        }
        if (accounts.size() > maxRows) accounts = accounts.subList(0, maxRows);

        // 拉关联的 Creator
        Map<Long, Creator> creatorMap = new java.util.HashMap<>();
        for (CreatorAccount a : accounts) {
            Long cid = a.getCreatorId();
            if (cid != null && !creatorMap.containsKey(cid)) {
                creatorRepository.findById(cid)
                        .filter(c -> orgTag.equals(c.getOwnerOrgTag()))
                        .ifPresent(c -> creatorMap.put(cid, c));
            }
        }
        List<CreatorExportService.ExportRow> rows = new ArrayList<>(accounts.size());
        for (CreatorAccount a : accounts) {
            rows.add(new CreatorExportService.ExportRow(a, creatorMap.get(a.getCreatorId())));
        }

        // 字段
        List<String> fields = null;
        if (input.hasNonNull("fieldsJson")) {
            try { fields = MAPPER.readValue(input.get("fieldsJson").asText(), new TypeReference<>() {}); }
            catch (Exception e) { return ToolResult.error("fieldsJson 解析失败：" + e.getMessage()); }
        }
        boolean includeCustom = !input.hasNonNull("includeCustomFields") || input.get("includeCustomFields").asBoolean(true);
        List<com.yizhaoqi.smartpai.model.creator.CustomFieldDefinition> creatorCfs = includeCustom
                ? customFieldService.list(orgTag, "creator") : List.of();
        List<com.yizhaoqi.smartpai.model.creator.CustomFieldDefinition> accountCfs = includeCustom
                ? customFieldService.list(orgTag, "account") : List.of();

        String baseName = text(input, "filename");
        if (baseName == null || baseName.isBlank()) baseName = "creators-" + LocalDateTime.now().format(TS);
        String safeName = baseName.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".xlsx";

        Path sandbox = paths.sessionSandbox(ctx.sessionId());
        Path exportDir = sandbox.resolve("exports");
        Files.createDirectories(exportDir);
        Path outFile = exportDir.resolve(safeName);

        try (OutputStream os = Files.newOutputStream(outFile)) {
            exportService.writeXlsx(os, rows, fields, creatorCfs, accountCfs);
        }

        long size = Files.size(outFile);
        String relative = sandbox.relativize(outFile).toString().replace('\\', '/');
        return ToolResult.of(
                Map.of("path", relative,
                        "absolutePath", outFile.toString(),
                        "rows", rows.size(),
                        "bytes", size),
                "导出成功：" + relative + "（" + rows.size() + " 行 / " + size + " 字节）");
    }

    private static String text(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asText(null) : null;
    }
    private static Long longVal(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asLong() : null;
    }
}
