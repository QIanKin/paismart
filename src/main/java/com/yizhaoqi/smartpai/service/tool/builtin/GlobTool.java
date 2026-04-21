package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.skill.SandboxPathResolver;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * fs_glob：按 glob 在沙箱（或允许的只读目录）内找文件。输出按 mtime 倒序，便于 LLM 定位最近产出。
 *
 * 语义：
 *  - pattern 不以 {@code **} 开头时自动包一层，方便 LLM 写 "*.csv"；
 *  - path 可选；缺省 = 会话沙箱；否则按 SandboxPathResolver 校验；
 *  - 最多返回 500 个结果。
 */
@Component
public class GlobTool implements Tool {

    private static final int LIMIT = 500;

    private final SandboxPathResolver paths;
    private final JsonNode schema;

    public GlobTool(SandboxPathResolver paths) {
        this.paths = paths;
        this.schema = ToolInputSchemas.object()
                .stringProp("pattern", "glob 模式，例如 '*.csv' 或 '**/creators_*.json'", true)
                .stringProp("path", "可选；要搜索的目录。缺省 = 会话沙箱根", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "fs_glob"; }
    @Override public String description() {
        return "按 glob 模式在沙箱目录（或 skill 目录）里找文件；按 mtime 倒序返回。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String pattern = input.path("pattern").asText(null);
        if (pattern == null || pattern.isBlank()) return ToolResult.error("pattern 不能为空");
        if (!pattern.startsWith("**/") && !pattern.startsWith("/")) {
            pattern = "**/" + pattern;
        }

        Path base;
        String pathInput = input.has("path") && !input.get("path").isNull() ? input.get("path").asText(null) : null;
        try {
            if (pathInput == null) base = paths.sessionSandbox(ctx.sessionId());
            else base = paths.resolve(pathInput, ctx.sessionId(), SandboxPathResolver.Access.READ);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isDirectory(base)) return ToolResult.error("不是目录: " + base);

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<Path> hits = new ArrayList<>();
        final Path basePath = base;

        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path rel = basePath.relativize(file);
                if (matcher.matches(rel)) hits.add(file);
                if (hits.size() >= LIMIT * 2) return FileVisitResult.TERMINATE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        hits.sort(Comparator.comparingLong((Path p) -> {
            try { return Files.getLastModifiedTime(p).toMillis(); }
            catch (IOException e) { return 0L; }
        }).reversed());

        List<Path> trimmed = hits.size() > LIMIT ? hits.subList(0, LIMIT) : hits;

        List<Map<String, Object>> rows = new ArrayList<>(trimmed.size());
        for (Path h : trimmed) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", h.toString());
            try {
                row.put("size", Files.size(h));
                row.put("mtime", Files.getLastModifiedTime(h).toInstant().toString());
            } catch (IOException ignored) {}
            rows.add(row);
        }

        Map<String, Object> data = Map.of("base", base.toString(), "pattern", pattern, "matches", rows);
        return ToolResult.of(data, "glob " + pattern + " → " + rows.size() + " 个文件");
    }
}
