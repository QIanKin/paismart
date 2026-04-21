package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.skill.SandboxPathResolver;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * fs_write：在沙箱里创建/覆盖/追加文件。拒绝越界路径；大小限制 4MB。
 */
@Component
public class FileWriteTool implements Tool {

    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private final SandboxPathResolver paths;
    private final JsonNode schema;

    public FileWriteTool(SandboxPathResolver paths) {
        this.paths = paths;
        this.schema = ToolInputSchemas.object()
                .stringProp("path", "目标路径；相对路径落在会话沙箱下；绝对路径必须在沙箱内", true)
                .stringProp("content", "要写入的文本内容", true)
                .booleanProp("append", "是否追加（默认 false，即覆盖写入）", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "fs_write"; }
    @Override public String description() {
        return "在会话沙箱内写文本文件（可覆盖或追加）。适合：生成 CSV/JSON、保存脚本输出、草稿导出前的中间产物。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        String content = input == null ? null : input.path("content").asText("");
        if (content != null && content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return PermissionResult.deny("content 超过 4MB，请拆分写入");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String p = input.path("path").asText(null);
        String content = input.path("content").asText("");
        boolean append = input.has("append") && input.get("append").asBoolean(false);

        Path path;
        try {
            path = paths.resolve(p, ctx.sessionId(), SandboxPathResolver.Access.WRITE);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }

        Files.createDirectories(path.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (append) {
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        long newSize = Files.size(path);
        int lines = content.isEmpty() ? 0 : (int) content.lines().count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path.toString());
        data.put("bytesWritten", bytes.length);
        data.put("fileSize", newSize);
        data.put("append", append);
        data.put("lines", lines);
        data.put("sha256", sha256(bytes));

        String summary = (append ? "追加" : "写入") + " " + path.getFileName()
                + " (+" + bytes.length + "B, 当前 " + newSize + "B)";
        return ToolResult.of(data, summary);
    }

    private String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
