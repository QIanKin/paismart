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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * fs_edit：严格的字符串替换。对标 claude-code FileEditTool 的 semantic：
 *  - old_string 必须在文件中唯一出现一次（除非 replace_all=true）；
 *  - 匹配必须保留原始缩进/空白（LLM 常忽略 → 反而是好事，强制精确）；
 *  - new_string 不能与 old_string 完全一致；
 *  - 成功后返回一段 3 行上下文的 diff 预览，便于 UI 展示。
 */
@Component
public class FileEditTool implements Tool {

    private final SandboxPathResolver paths;
    private final JsonNode schema;

    public FileEditTool(SandboxPathResolver paths) {
        this.paths = paths;
        this.schema = ToolInputSchemas.object()
                .stringProp("path", "文件路径（相对=沙箱；绝对必须在沙箱内）", true)
                .stringProp("old_string", "要被替换的原文（保留精确空白与换行）", true)
                .stringProp("new_string", "替换后的新内容", true)
                .booleanProp("replace_all", "是否替换全部出现位置；默认 false（唯一命中才替换）", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "fs_edit"; }
    @Override public String description() {
        return "对沙箱内文件做精确字符串替换。必须给出完整的 old_string 原文（含缩进），若在文件中不唯一需要更多上下文或 replace_all=true。";
    }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        String p = input.path("path").asText(null);
        String oldStr = input.path("old_string").asText(null);
        String newStr = input.path("new_string").asText(null);
        boolean replaceAll = input.has("replace_all") && input.get("replace_all").asBoolean(false);

        if (oldStr == null) return ToolResult.error("old_string 不能为空");
        if (newStr == null) return ToolResult.error("new_string 不能为空");
        if (oldStr.equals(newStr)) return ToolResult.error("old_string 和 new_string 完全相同，无需编辑");

        Path path;
        try {
            path = paths.resolve(p, ctx.sessionId(), SandboxPathResolver.Access.WRITE);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isRegularFile(path)) return ToolResult.error("文件不存在: " + path);

        String content = Files.readString(path, StandardCharsets.UTF_8);

        EditOutcome outcome = applyEdit(content, oldStr, newStr, replaceAll);
        if (outcome.error != null) return ToolResult.error(outcome.error);

        Files.writeString(path, outcome.newContent, StandardCharsets.UTF_8);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path.toString());
        data.put("replacements", outcome.replacements);
        data.put("diffPreview", outcome.diffPreview);
        data.put("oldSize", content.length());
        data.put("newSize", outcome.newContent.length());

        return ToolResult.of(data,
                "edit " + path.getFileName() + " 替换 " + outcome.replacements + " 处");
    }

    /** 纯函数形式，方便单测 */
    public static EditOutcome applyEdit(String content, String oldStr, String newStr, boolean replaceAll) {
        int first = content.indexOf(oldStr);
        if (first < 0) {
            return EditOutcome.fail("old_string 在文件中未找到。请复制粘贴完整精确的原文（包含缩进）再试。");
        }
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(oldStr, idx)) >= 0) {
            count++;
            idx += oldStr.length();
        }
        if (!replaceAll && count > 1) {
            return EditOutcome.fail("old_string 在文件中出现 " + count + " 次。请补足更多上下文保持唯一，或设 replace_all=true。");
        }
        String newContent = replaceAll
                ? content.replace(oldStr, newStr)
                : content.substring(0, first) + newStr + content.substring(first + oldStr.length());

        int replacements = replaceAll ? count : 1;
        String preview = preview(content, first, oldStr, newStr);
        return EditOutcome.ok(newContent, replacements, preview);
    }

    private static String preview(String oldContent, int matchStart, String oldStr, String newStr) {
        int lineStart = oldContent.lastIndexOf('\n', Math.max(0, matchStart - 1)) + 1;
        int lineEnd = oldContent.indexOf('\n', matchStart + oldStr.length());
        if (lineEnd < 0) lineEnd = oldContent.length();
        String before = oldContent.substring(lineStart, lineEnd);
        String afterLine = before.replace(oldStr, newStr);
        return "- " + trim(before) + "\n+ " + trim(afterLine);
    }

    private static String trim(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    public static final class EditOutcome {
        public final String newContent;
        public final int replacements;
        public final String diffPreview;
        public final String error;

        private EditOutcome(String newContent, int replacements, String diffPreview, String error) {
            this.newContent = newContent;
            this.replacements = replacements;
            this.diffPreview = diffPreview;
            this.error = error;
        }
        public static EditOutcome ok(String c, int r, String d) { return new EditOutcome(c, r, d, null); }
        public static EditOutcome fail(String e) { return new EditOutcome(null, 0, null, e); }
    }
}
