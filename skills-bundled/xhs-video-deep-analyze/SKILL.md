---
name: xhs-video-deep-analyze
description: 小红书视频"深度爆款拆解"标准流程：调用 xhs_video_analyze 工具拿"视频+ASR+视觉LLM+OCR"四合一报告，再给到用户结构化结论。当用户给一个小红书视频链接 / note_id，要"拆这条爆款 / 分析这个博主某条视频"时，先读本 skill。
version: "1.0.0"
metadata:
  bee:
    tags: [xhs, video, analysis, asr, vision, ocr, hot-content, reference]
    requires: {}
    loadMode: reference
---

# xhs-video-deep-analyze

> **参考型 skill**：本身不跑脚本，只规定 Agent 拿到「拆这条小红书视频」类需求时的标准做法。
> 真正的下载/抽帧/ASR/视觉 LLM 都封装在内置工具 `xhs_video_analyze` 里，本 skill 是它的「使用说明书」。

## 何时使用

- 用户给出一个小红书视频链接（含 `xsec_token`）或 `note_id`，希望 Agent **逐条拆解爆款逻辑**；
- 用户在 BLOGGER_BRIEF / CONTENT_REVIEW 会话里说"参考下这个博主前几天那条视频再写"；
- 用户在 ALLOCATION 会话里希望"判断这位博主最近内容是否还稳态在 A 赛道"。

## 步骤

1. **解析 + 一键拆解**：调用工具 `xhs_video_analyze`，参数最少只需 `url` 或 `noteId`：
   ```json
   { "url": "https://www.xiaohongshu.com/explore/xxx?xsec_token=...", "withAsr": true, "withReport": true, "withVision": true }
   ```
   - `withAsr=true`：默认开。本地 Whisper / DashScope ASR 转写音频。
   - `withVision=true`：默认开。在视频时长内均匀抽 6 张关键帧（可调），喂给「同 base_url、同 api_key、可换 model」的视觉 LLM；
     画面里的字幕、产品包装、标签都会被 OCR 进 `report.visual.on_screen_text`。
   - `withReport=true`：默认开。把音频 transcript + 关键帧画面一起送 LLM 做爆款拆解。

   返回 `report` 字段是结构化 JSON，关键节点：`hook / topic / rhythm / visual / selling_points / cta / reusable_template / rewrites`。

2. **结合企业内部资产输出结论**：
   - 把 `report` 关联到企业知识库 `xhs-note-methodology` 中的「博主 7 维评估表」「爆款结构 S-H-C-R-C」；
   - 若是 ALLOCATION 场景：拿 `topic.angle / audience` 比对项目 brief，给"推荐入围/需谈/不匹配"结论；
   - 若是 BLOGGER_BRIEF 场景：用 `reusable_template + rewrites` 直接给该博主下条选题 2~3 套；
   - 若是 CONTENT_REVIEW：拿 `hook / rhythm / cta` 与本企业行为契约比对，标"必须改/建议改"。

3. **质量自检（必做）**：
   - `report.visual.on_screen_text` 为空且视频确实有字幕？→ 提示用户 `xhs_video_analyze.withVision` 是否被禁用，或 `ai.vision.model` 是否配的是非视觉模型；
   - `report` 字段为 `null` 但 `transcript` 已就绪？→ 多半是 `report_failed`，可重试一次或降级到只用 transcript；
   - `partial=true`：先把已就绪的 video / audio / transcript 给用户看，并解释 `errorType`。

## 取舍

- **下载与转写很贵**：单条视频 ≤ 600s 才允许下载（工具默认 maxDurationSec），更长视频要先和用户确认。
- **只要画面不要 LLM 报告**：把 `withReport=false withVision=true`，会得到关键帧文件（在结果 `visionFrames` 数组里），适合做「我先把帧给你」的诊断流。
- **API 配额敏感**：把 `withVision=false` 退回纯文本拆解（沿用 transcript-only 路径，约省 60% token）。

## 不要做

- 不要把 cookie / 浏览器登录态混进来——本 skill 全程走 TikHub + 内部模型，零封号风险；
- 不要在 prompt 里再额外要求 LLM 输出 Markdown 渲染——`report` 已经是结构化 JSON，前端会自己渲染卡片；
- 不要用本 skill 一次性拆 > 5 条视频；批量请用调度任务（`scheduled_skills`）。
