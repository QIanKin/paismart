package com.yizhaoqi.smartpai.service.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL.md 解析器。
 *
 * 格式：
 *   ---
 *   name: xxx
 *   description: "..."
 *   metadata: {openclaw: {requires: {bins: [...]}, install: [...]}}
 *   ---
 *   # Markdown 正文...
 *
 * 兼容 openclaw / claude-code / claude-best 三套格式：都遵循 YAML front-matter + markdown body。
 * 由于 YAML 可能内嵌 JSON（openclaw 习惯），我们用 SnakeYAML 的 loose 模式直接解析。
 */
@Component
public class SkillParser {

    private static final Logger logger = LoggerFactory.getLogger(SkillParser.class);
    private static final String DELIMITER = "---";

    private final ObjectMapper mapper;

    public SkillParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ParsedSkill parse(Path skillDir) throws Exception {
        Path md = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(md)) {
            throw new IllegalArgumentException("SKILL.md not found: " + skillDir);
        }
        String raw = new String(Files.readAllBytes(md), StandardCharsets.UTF_8);
        Map<String, Object> frontMatter;
        String body;

        if (raw.startsWith(DELIMITER)) {
            int end = raw.indexOf('\n' + DELIMITER, DELIMITER.length());
            if (end < 0) {
                throw new IllegalArgumentException("SKILL.md front-matter 未闭合: " + md);
            }
            String fm = raw.substring(DELIMITER.length(), end).trim();
            body = raw.substring(end + DELIMITER.length() + 1).trim();
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(fm);
            if (loaded instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) m;
                frontMatter = casted;
            } else {
                frontMatter = Collections.emptyMap();
            }
        } else {
            frontMatter = Collections.emptyMap();
            body = raw.trim();
        }

        String name = asString(frontMatter.get("name"));
        if (name == null || name.isBlank()) name = skillDir.getFileName().toString();
        String description = asString(frontMatter.get("description"));
        String version = asString(frontMatter.get("version"));
        String homepage = asString(frontMatter.get("homepage"));

        // metadata 原样保留为 JSON（YAML 有嵌套 map，用 Jackson 转一下）
        Object metadata = frontMatter.get("metadata");
        String metadataJson = metadata == null ? null : mapper.writeValueAsString(metadata);

        // requires.bins 便捷字段：从 metadata.<any>.requires.bins 归并
        List<String> requiredBins = extractRequiredBins(frontMatter);

        // 扫描 scripts 子目录
        List<String> scriptsInventory = new ArrayList<>();
        Path scriptsDir = skillDir.resolve("scripts");
        if (Files.isDirectory(scriptsDir)) {
            try (var stream = Files.list(scriptsDir)) {
                stream.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .forEach(scriptsInventory::add);
            }
        }

        String bodyHash = sha256(body);

        return new ParsedSkill(
                name,
                description,
                version,
                homepage,
                metadataJson,
                mapper.writeValueAsString(Map.of("bins", requiredBins)),
                String.join("\n", scriptsInventory),
                body,
                bodyHash,
                skillDir.toAbsolutePath().toString(),
                Files.getLastModifiedTime(md).toInstant().toEpochMilli()
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRequiredBins(Map<String, Object> fm) {
        Object metadata = fm.get("metadata");
        List<String> out = new ArrayList<>();
        if (metadata instanceof Map<?, ?> mm) {
            for (Object vendorVal : ((Map<String, Object>) mm).values()) {
                if (vendorVal instanceof Map<?, ?> vendorMap) {
                    Object requires = ((Map<String, Object>) vendorMap).get("requires");
                    if (requires instanceof Map<?, ?> requiresMap) {
                        Object bins = ((Map<String, Object>) requiresMap).get("bins");
                        if (bins instanceof List<?> binList) {
                            for (Object b : binList) if (b != null) out.add(b.toString());
                        }
                    }
                }
            }
        }
        return out;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            logger.warn("sha256 失败: {}", e.getMessage());
            return "";
        }
    }

    /** 供 SkillLoader 使用的纯解析结果 */
    public record ParsedSkill(
            String name,
            String description,
            String version,
            String homepage,
            String metadataJson,
            String requiredBinsJson,
            String scriptsInventory,
            String bodyMd,
            String bodyHash,
            String rootPath,
            long mtimeMillis
    ) {
        public LinkedHashMap<String, Object> asMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", description);
            m.put("version", version);
            m.put("homepage", homepage);
            m.put("scripts", scriptsInventory);
            m.put("rootPath", rootPath);
            return m;
        }
    }
}
