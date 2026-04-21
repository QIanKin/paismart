package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.skill.SandboxPathResolver;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * fs_read：读取沙箱（或 skill 只读根）内的一段文本文件，输出带行号，便于后续 fs_edit 精确定位。
 *
 * 参考 claude-code FileReadTool：
 *  - 默认从第 1 行开始；
 *  - 超长文件截断；
 *  - offset 负数表示从末尾倒数（跟 `tail -n` 相似）。
 */
@Component
public class FileReadTool implements Tool {

    private static final int MAX_LINES = 2000;
    private static final int MAX_LINE_LEN = 2000;

    private final SandboxPathResolver paths;
    private final JsonNode schema;

    public FileReadTool(SandboxPathResolver paths) {
        this.paths = paths;
        this.schema = ToolInputSchemas.object()
                .stringProp("path", "文件路径；相对路径视为会话沙箱下；绝对路径必须落在沙箱或 skills 根/已注册 skill 目录内", true)
                .integerProp("offset", "起始行号（1-based）；负数表示从尾部倒数。默认 1", false)
                .integerProp("limit", "读取最多多少行。默认 2000", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "fs_read"; }
    @Override public String description() {
        return "读取沙箱目录内的文本文件（或 skills 目录下的资源）。返回带行号的内容，便于后续用 fs_edit。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String p = input.path("path").asText(null);
        int offset = input.has("offset") ? input.get("offset").asInt(1) : 1;
        int limit = input.has("limit") ? input.get("limit").asInt(MAX_LINES) : MAX_LINES;
        if (limit <= 0 || limit > MAX_LINES) limit = MAX_LINES;

        Path path;
        try {
            path = paths.resolve(p, ctx.sessionId(), SandboxPathResolver.Access.READ);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("不是普通文件或不存在: " + path);
        }
        long size = Files.size(path);
        if (size > 20L * 1024 * 1024) {
            return ToolResult.error("文件过大（>20MB），请先用 bash head/tail 抽样: " + path);
        }

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int total = lines.size();
        int start = offset;
        if (offset < 0) start = Math.max(1, total + offset + 1);
        if (start < 1) start = 1;
        int endExclusive = Math.min(total + 1, start + limit);

        StringBuilder out = new StringBuilder();
        for (int i = start; i < endExclusive; i++) {
            String line = lines.get(i - 1);
            if (line.length() > MAX_LINE_LEN) {
                line = line.substring(0, MAX_LINE_LEN) + "... [line truncated]";
            }
            out.append(String.format("%6d|", i)).append(line).append('\n');
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path.toString());
        data.put("totalLines", total);
        data.put("from", start);
        data.put("to", endExclusive - 1);
        data.put("content", out.toString());

        String summary = "读取 " + path.getFileName() + " [" + start + "," + (endExclusive - 1) + "] / 共 " + total + " 行";
        return ToolResult.of(data, summary);
    }

    // 测试钩子
    static List<String> _chunkLines(List<String> lines, int offset, int limit, int total) {
        int start = offset;
        if (offset < 0) start = Math.max(1, total + offset + 1);
        if (start < 1) start = 1;
        int endExclusive = Math.min(total + 1, start + limit);
        List<String> out = new ArrayList<>();
        for (int i = start; i < endExclusive; i++) out.add(lines.get(i - 1));
        return out;
    }
}
