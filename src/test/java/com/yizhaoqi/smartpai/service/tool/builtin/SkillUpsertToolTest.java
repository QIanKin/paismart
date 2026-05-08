package com.yizhaoqi.smartpai.service.tool.builtin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillUpsertToolTest {

    @Test
    void buildSkillMdEscapesYamlQuotesAndKeepsBody() {
        String md = SkillUpsertTool.buildSkillMd(
                "xhs-note-audit",
                "处理小红书笔记的 '审计' 流程",
                "0.2.0",
                "## Steps\n\n1. 先 list_skills。\n2. 再执行任务。"
        );

        assertTrue(md.startsWith("---\n"));
        assertTrue(md.contains("name: 'xhs-note-audit'"));
        assertTrue(md.contains("description: '处理小红书笔记的 ''审计'' 流程'"));
        assertTrue(md.contains("version: '0.2.0'"));
        assertTrue(md.contains("# xhs-note-audit"));
        assertTrue(md.contains("## Steps"));
    }
}
