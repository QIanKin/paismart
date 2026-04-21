package com.yizhaoqi.smartpai.service.creator;

import com.yizhaoqi.smartpai.model.creator.Creator;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.model.creator.CustomFieldDefinition;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreatorExportServiceTest {

    private CreatorAccount account(long id, String platform, String handle, long followers) {
        CreatorAccount a = new CreatorAccount();
        a.setId(id);
        a.setPlatform(platform);
        a.setHandle(handle);
        a.setDisplayName("昵称-" + id);
        a.setFollowers(followers);
        a.setAvgLikes(1000L);
        a.setCategoryMain("母婴");
        a.setVerified(true);
        a.setOwnerOrgTag("demo");
        a.setCustomFieldsJson("{\"city\":\"北京\",\"level\":\"S\"}");
        return a;
    }

    private Creator creator(long id, String name) {
        Creator c = new Creator();
        c.setId(id);
        c.setDisplayName(name);
        c.setCooperationStatus("active");
        c.setCustomFieldsJson("{\"bd\":\"张三\"}");
        c.setOwnerOrgTag("demo");
        return c;
    }

    @Test
    void writesHeadersDataAndCustomFields() throws Exception {
        CreatorExportService svc = new CreatorExportService();
        List<CreatorExportService.ExportRow> rows = List.of(
                new CreatorExportService.ExportRow(account(1L, "xhs", "alice", 12345L), creator(100L, "Alice")),
                new CreatorExportService.ExportRow(account(2L, "douyin", "bob", 67890L), null));

        CustomFieldDefinition creatorField = new CustomFieldDefinition();
        creatorField.setLabel("BD"); creatorField.setFieldKey("bd"); creatorField.setDataType("string");
        CustomFieldDefinition accountField = new CustomFieldDefinition();
        accountField.setLabel("城市"); accountField.setFieldKey("city"); accountField.setDataType("string");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        svc.writeXlsx(out, rows, List.of("accountId", "platform", "handle", "followers", "cooperationStatus"),
                List.of(creatorField), List.of(accountField));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            var sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertEquals("账号 ID", header.getCell(0).getStringCellValue());
            assertEquals("平台", header.getCell(1).getStringCellValue());
            assertEquals("账号名", header.getCell(2).getStringCellValue());
            assertEquals("粉丝数", header.getCell(3).getStringCellValue());
            assertEquals("合作状态", header.getCell(4).getStringCellValue());
            assertEquals("[C] BD", header.getCell(5).getStringCellValue());
            assertEquals("[A] 城市", header.getCell(6).getStringCellValue());

            Row r1 = sheet.getRow(1);
            assertEquals(1.0, r1.getCell(0).getNumericCellValue());
            assertEquals("xhs", r1.getCell(1).getStringCellValue());
            assertEquals("alice", r1.getCell(2).getStringCellValue());
            assertEquals(12345.0, r1.getCell(3).getNumericCellValue());
            assertEquals("active", r1.getCell(4).getStringCellValue());
            assertEquals("张三", r1.getCell(5).getStringCellValue());
            assertEquals("北京", r1.getCell(6).getStringCellValue());

            Row r2 = sheet.getRow(2);
            assertEquals("douyin", r2.getCell(1).getStringCellValue());
            // 第 2 行没有 creator，BD 列为空
            assertTrue(r2.getCell(5) == null
                    || r2.getCell(5).toString().isEmpty()
                    || r2.getCell(5).getCellType().toString().equals("BLANK"));
            assertEquals("北京", r2.getCell(6).getStringCellValue());
        }
    }

    @Test
    void defaultFieldsFallbackToBuiltin() throws Exception {
        CreatorExportService svc = new CreatorExportService();
        List<CreatorExportService.ExportRow> rows = List.of(
                new CreatorExportService.ExportRow(account(1L, "xhs", "alice", 12345L), creator(10L, "Alice")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        svc.writeXlsx(out, rows, null, List.of(), List.of());

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            var sheet = wb.getSheetAt(0);
            // 默认列 > 20
            assertTrue(sheet.getRow(0).getLastCellNum() > 20);
        }
    }
}
