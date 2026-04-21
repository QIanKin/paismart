package com.yizhaoqi.smartpai.service.creator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.model.creator.CustomFieldDefinition;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 博主数据 Excel 导出：SXSSF 流式写，避免大批量导出时 OOM。
 *
 * 导出列由两部分组成：
 *  - 内置列：Creator + Account 的标准字段（按请求里的 fields 过滤）；
 *  - 自定义列：从 {@link CustomFieldDefinition} 里拉出 account/creator 维度的自定义字段 key。
 *
 * 注意：每次导出独立新建一个 workbook，写完立刻 dispose，调用方负责 close 输出流。
 */
@Service
public class CreatorExportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 所有可导出的"内置列"及取值器。顺序 = 默认导出顺序。 */
    private static final List<Column> BUILTIN_COLUMNS = List.of(
            col("accountId", "账号 ID", (c, a) -> a == null ? null : a.getId()),
            col("platform", "平台", (c, a) -> a == null ? null : a.getPlatform()),
            col("handle", "账号名", (c, a) -> a == null ? null : a.getHandle()),
            col("accountDisplayName", "账号昵称", (c, a) -> a == null ? null : a.getDisplayName()),
            col("creatorId", "Creator ID", (c, a) -> c == null ? null : c.getId()),
            col("creatorDisplayName", "人设名", (c, a) -> c == null ? null : c.getDisplayName()),
            col("realName", "真名", (c, a) -> c == null ? null : c.getRealName()),
            col("cooperationStatus", "合作状态", (c, a) -> c == null ? null : c.getCooperationStatus()),
            col("region", "地区", (c, a) -> a == null ? null : a.getRegion()),
            col("gender", "性别", (c, a) -> c == null ? null : c.getGender()),
            col("birthYear", "出生年", (c, a) -> c == null ? null : c.getBirthYear()),
            col("followers", "粉丝数", (c, a) -> a == null ? null : a.getFollowers()),
            col("likes", "累计点赞", (c, a) -> a == null ? null : a.getLikes()),
            col("posts", "发帖数", (c, a) -> a == null ? null : a.getPosts()),
            col("avgLikes", "均赞", (c, a) -> a == null ? null : a.getAvgLikes()),
            col("avgComments", "均评论", (c, a) -> a == null ? null : a.getAvgComments()),
            col("hitRatio", "爆款率", (c, a) -> a == null ? null : a.getHitRatio()),
            col("engagementRate", "互动率", (c, a) -> a == null ? null : a.getEngagementRate()),
            col("verified", "是否认证", (c, a) -> a == null ? null : a.getVerified()),
            col("categoryMain", "主分类", (c, a) -> a == null ? null : a.getCategoryMain()),
            col("categorySub", "子分类", (c, a) -> a == null ? null : a.getCategorySub()),
            col("bio", "简介", (c, a) -> a == null ? null : a.getBio()),
            col("personaTags", "人设标签", (c, a) -> c == null ? null : c.getPersonaTagsJson()),
            col("trackTags", "赛道", (c, a) -> c == null ? null : c.getTrackTagsJson()),
            col("platformTags", "平台标签", (c, a) -> a == null ? null : a.getPlatformTagsJson()),
            col("homepageUrl", "主页", (c, a) -> a == null ? null : a.getHomepageUrl()),
            col("priceNote", "报价备注", (c, a) -> c == null ? null : c.getPriceNote()),
            col("internalNotes", "内部备注", (c, a) -> c == null ? null : c.getInternalNotes()),
            col("latestSnapshotAt", "最近同步", (c, a) -> a == null ? null : a.getLatestSnapshotAt()),
            col("updatedAt", "更新时间", (c, a) -> a == null ? null : a.getUpdatedAt())
    );

    public static List<String> defaultFieldKeys() {
        List<String> keys = new ArrayList<>();
        for (Column c : BUILTIN_COLUMNS) keys.add(c.key);
        return keys;
    }

    public void writeXlsx(OutputStream out, List<ExportRow> rows,
                          List<String> requestedFields,
                          List<CustomFieldDefinition> creatorFields,
                          List<CustomFieldDefinition> accountFields) throws Exception {

        // 1) 取要导出的内置列
        List<Column> columns = new ArrayList<>();
        if (requestedFields == null || requestedFields.isEmpty()) {
            columns.addAll(BUILTIN_COLUMNS);
        } else {
            Map<String, Column> idx = new LinkedHashMap<>();
            for (Column c : BUILTIN_COLUMNS) idx.put(c.key, c);
            for (String req : requestedFields) {
                Column c = idx.get(req);
                if (c != null) columns.add(c);
            }
        }

        try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
            Sheet sheet = wb.createSheet("creators");
            CellStyle headStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headStyle.setFont(bold);

            // 2) header
            Row header = sheet.createRow(0);
            int idx = 0;
            for (Column c : columns) {
                Cell cell = header.createCell(idx++);
                cell.setCellValue(c.header);
                cell.setCellStyle(headStyle);
            }
            for (CustomFieldDefinition cf : safe(creatorFields)) {
                Cell cell = header.createCell(idx++);
                cell.setCellValue("[C] " + cf.getLabel());
                cell.setCellStyle(headStyle);
            }
            for (CustomFieldDefinition cf : safe(accountFields)) {
                Cell cell = header.createCell(idx++);
                cell.setCellValue("[A] " + cf.getLabel());
                cell.setCellStyle(headStyle);
            }

            // 3) data rows
            int rowIdx = 1;
            for (ExportRow row : rows) {
                Row r = sheet.createRow(rowIdx++);
                int col = 0;
                for (Column c : columns) {
                    Object v = c.getter.apply(row.creator(), row.account());
                    writeCell(r.createCell(col++), v);
                }
                JsonNode creatorCustom = parse(row.creator() == null ? null : row.creator().getCustomFieldsJson());
                for (CustomFieldDefinition cf : safe(creatorFields)) {
                    writeCell(r.createCell(col++), extract(creatorCustom, cf.getFieldKey()));
                }
                JsonNode accountCustom = parse(row.account() == null ? null : row.account().getCustomFieldsJson());
                for (CustomFieldDefinition cf : safe(accountFields)) {
                    writeCell(r.createCell(col++), extract(accountCustom, cf.getFieldKey()));
                }
            }

            // 4) 粗略列宽 auto
            for (int i = 0; i < columns.size(); i++) sheet.setColumnWidth(i, 20 * 256);

            wb.write(out);
            wb.dispose();
        }
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readTree(json); } catch (Exception e) { return null; }
    }

    private static Object extract(JsonNode n, String key) {
        if (n == null || !n.has(key)) return null;
        JsonNode v = n.get(key);
        if (v.isNull()) return null;
        if (v.isTextual()) return v.asText();
        if (v.isNumber()) return v.numberValue();
        if (v.isBoolean()) return v.asBoolean();
        return v.toString();
    }

    private static void writeCell(Cell cell, Object v) {
        if (v == null) { cell.setBlank(); return; }
        if (v instanceof Number n) { cell.setCellValue(n.doubleValue()); return; }
        if (v instanceof Boolean b) { cell.setCellValue(b); return; }
        if (v instanceof LocalDateTime dt) { cell.setCellValue(dt.format(DTF)); return; }
        cell.setCellValue(String.valueOf(v));
    }

    private static <T> List<T> safe(List<T> in) {
        return in == null ? List.of() : in;
    }

    private interface Getter {
        Object apply(Creator c, CreatorAccount a);
    }

    private static Column col(String key, String header, Getter getter) {
        return new Column(key, header, getter);
    }

    private record Column(String key, String header, Getter getter) {}

    /** 导出一行所需的"账号 + 该账号可选的 Creator"。 */
    public record ExportRow(CreatorAccount account, Creator creator) {}
}
