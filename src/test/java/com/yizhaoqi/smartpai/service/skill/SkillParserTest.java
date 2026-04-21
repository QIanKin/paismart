package com.yizhaoqi.smartpai.service.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillParserTest {

    private final SkillParser parser = new SkillParser(new ObjectMapper());

    @Test
    void parsesFrontMatterAndBody(@TempDir Path tmp) throws Exception {
        Path skillDir = tmp.resolve("hello");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("scripts/run.sh"), "#!/bin/sh\necho hi\n");
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: hello
                description: "say hi"
                version: 0.1.0
                metadata:
                  vendor:
                    requires:
                      bins: [sh, echo]
                ---

                # Hello Skill

                use `run.sh` to say hi.
                """, StandardCharsets.UTF_8);

        SkillParser.ParsedSkill p = parser.parse(skillDir);
        assertEquals("hello", p.name());
        assertEquals("say hi", p.description());
        assertEquals("0.1.0", p.version());
        assertTrue(p.bodyMd().startsWith("# Hello Skill"));
        assertTrue(p.scriptsInventory().contains("run.sh"));
        assertTrue(p.requiredBinsJson().contains("\"sh\""));
        assertTrue(p.requiredBinsJson().contains("\"echo\""));
        assertNotNull(p.bodyHash());
        assertEquals(64, p.bodyHash().length());
    }

    @Test
    void toleratesMissingFrontMatter(@TempDir Path tmp) throws Exception {
        Path d = tmp.resolve("no-fm");
        Files.createDirectories(d);
        Files.writeString(d.resolve("SKILL.md"), "# Naked body with no front matter\ndo stuff\n",
                StandardCharsets.UTF_8);

        SkillParser.ParsedSkill p = parser.parse(d);
        assertEquals("no-fm", p.name());
        assertTrue(p.bodyMd().contains("Naked body"));
    }

    @Test
    void failsWhenSkillMdMissing(@TempDir Path tmp) {
        Path d = tmp.resolve("empty-skill");
        assertThrows(IllegalArgumentException.class, () -> parser.parse(d));
    }

    @Test
    void failsWhenFrontMatterUnterminated(@TempDir Path tmp) throws Exception {
        Path d = tmp.resolve("bad");
        Files.createDirectories(d);
        Files.writeString(d.resolve("SKILL.md"),
                "---\nname: bad\ndescription: missing closing delimiter\n",
                StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> parser.parse(d));
    }
}
