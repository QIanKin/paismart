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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * fs_grep：沙箱内的内容搜索。原生 Java Regex 实现，避开对 ripgrep 的外部依赖。
 *
 * 三种输出模式（对齐 claude-code Grep）：
 *  - files_with_matches（默认）：只列命中文件，最省 token
 *  - count：每个文件的命中计数
 *  - content：命中行（可带 -B/-A 上下文）
 */
@Component
public class GrepTool implements Tool {

    private static final int MAX_MATCHES_CONTENT = 500;
    private static final int MAX_FILES_SCAN = 5000;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    private final SandboxPathResolver paths;
    private final JsonNode schema;

    public GrepTool(SandboxPathResolver paths) {
        this.paths = paths;
        this.schema = ToolInputSchemas.object()
                .stringProp("pattern", "要搜索的正则（Java regex）", true)
                .stringProp("path", "可选；搜索目录。缺省 = 会话沙箱", false)
                .stringProp("glob", "可选；仅检索匹配此 glob 的文件，例如 '*.md'", false)
                .booleanProp("caseInsensitive", "忽略大小写，默认 false", false)
                .enumProp("output", "输出模式", List.of("files_with_matches", "count", "content"), false)
                .integerProp("contextBefore", "content 模式下每条命中前的上下文行数", false)
                .integerProp("contextAfter", "content 模式下每条命中后的上下文行数", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "fs_grep"; }
    @Override public String description() {
        return "在沙箱目录里按正则搜索文本内容。建议先用 output=files_with_matches 过滤，再对候选用 content 获取具体行。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String rawPattern = input.path("pattern").asText(null);
        if (rawPattern == null || rawPattern.isEmpty()) return ToolResult.error("pattern 不能为空");
        boolean ci = input.has("caseInsensitive") && input.get("caseInsensitive").asBoolean(false);
        String outMode = input.has("output") ? input.get("output").asText("files_with_matches") : "files_with_matches";
        int before = input.has("contextBefore") ? Math.max(0, Math.min(20, input.get("contextBefore").asInt(0))) : 0;
        int after = input.has("contextAfter") ? Math.max(0, Math.min(20, input.get("contextAfter").asInt(0))) : 0;
        String globStr = input.has("glob") && !input.get("glob").isNull() ? input.get("glob").asText(null) : null;

        Pattern regex;
        try {
            regex = Pattern.compile(rawPattern, ci ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("正则无效: " + e.getMessage());
        }

        Path base;
        try {
            String pathInput = input.has("path") && !input.get("path").isNull() ? input.get("path").asText(null) : null;
            if (pathInput == null) base = paths.sessionSandbox(ctx.sessionId());
            else base = paths.resolve(pathInput, ctx.sessionId(), SandboxPathResolver.Access.READ);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isDirectory(base)) return ToolResult.error("不是目录: " + base);

        PathMatcher globMatcher = globStr == null ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + (globStr.startsWith("**/") ? globStr : "**/" + globStr));

        GrepResult result = scan(base, regex, globMatcher, outMode, before, after);
        return ToolResult.of(result.data, result.summary);
    }

    private GrepResult scan(Path base, Pattern regex, PathMatcher globMatcher,
                            String outMode, int before, int after) throws IOException {
        List<Map<String, Object>> perFile = new ArrayList<>();
        int[] scanned = {0};
        int[] totalMatches = {0};

        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (scanned[0] >= MAX_FILES_SCAN) return FileVisitResult.TERMINATE;
                if (globMatcher != null) {
                    Path rel = base.relativize(file);
                    if (!globMatcher.matches(rel)) return FileVisitResult.CONTINUE;
                }
                scanned[0]++;
                try {
                    if (Files.size(file) > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                    Map<String, Object> hit = scanFile(file, regex, outMode, before, after);
                    if (hit != null) {
                        perFile.add(hit);
                        Object n = hit.get("count");
                        if (n instanceof Integer i) totalMatches[0] += i;
                    }
                } catch (IOException ignored) {
                    // skip unreadable
                }
                if ("content".equals(outMode) && totalMatches[0] >= MAX_MATCHES_CONTENT) {
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        perFile.sort(Comparator.comparingInt((Map<String, Object> m) ->
                ((Integer) m.getOrDefault("count", 0))).reversed());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("base", base.toString());
        data.put("output", outMode);
        data.put("filesScanned", scanned[0]);
        data.put("filesMatched", perFile.size());
        data.put("totalMatches", totalMatches[0]);

        if ("files_with_matches".equalsIgnoreCase(outMode)) {
            List<String> files = perFile.stream().map(m -> (String) m.get("path")).toList();
            data.put("files", files);
        } else {
            data.put("results", perFile);
        }
        String summary = outMode + ": " + perFile.size() + " files, " + totalMatches[0] + " matches (scanned " + scanned[0] + ")";
        return new GrepResult(data, summary);
    }

    private Map<String, Object> scanFile(Path file, Pattern regex, String outMode,
                                         int before, int after) throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception notText) {
            return null;
        }
        int count = 0;
        List<Map<String, Object>> hits = "content".equalsIgnoreCase(outMode) ? new ArrayList<>() : null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = regex.matcher(line);
            if (!m.find()) continue;
            count++;
            if (hits != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("lineNo", i + 1);
                row.put("line", clip(line));
                if (before > 0) {
                    int from = Math.max(0, i - before);
                    row.put("before", lines.subList(from, i).stream().map(GrepTool::clip).toList());
                }
                if (after > 0) {
                    int to = Math.min(lines.size(), i + 1 + after);
                    row.put("after", lines.subList(i + 1, to).stream().map(GrepTool::clip).toList());
                }
                hits.add(row);
            }
        }
        if (count == 0) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.toString());
        out.put("count", count);
        if (hits != null) out.put("hits", hits);
        return out;
    }

    private static String clip(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    private record GrepResult(Map<String, Object> data, String summary) {}

    // 仅给 unit test 用的入口
    static Pattern _compile(String pattern, boolean ci) {
        return Pattern.compile(pattern, ci ? Pattern.CASE_INSENSITIVE : 0);
    }
    static boolean _lineMatches(String line, String pattern) {
        return Pattern.compile(pattern).matcher(line.toLowerCase(Locale.ROOT)).find();
    }
}
