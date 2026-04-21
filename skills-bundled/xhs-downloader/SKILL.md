---
name: xhs-downloader
description: 下载小红书视频 / 图文，并可选产出"资源包"——视频 + 音频 + 字幕 + 纯文本脚本。适用于选题逆向工程、爆款视频拆解、评测视频做二创参考、内容存档。当用户说"下载小红书视频"、"把这条 xhs 视频存下来"、"小红书 xxx 内容抓取"、"扒视频"、"xhslink 下载"时使用。
version: "0.1.0"
homepage: "https://github.com/yt-dlp/yt-dlp"
metadata:
  bee:
    tags: [xhs, download, video, content-archive, mcn]
    requires:
      bins: [python, python3, yt-dlp, ffmpeg]
      env: [COOKIES]
---

# xhs-downloader

## 何时使用

- 用户把一个 `xiaohongshu.com/explore/...` 或 `xhslink.com/...` 链接丢给 agent，要求下载或分析
- 做爆款视频逆向：下载视频 + 抽音频 + 转写脚本 → LLM 分析开头钩子 / 高潮位置 / 结尾 CTA
- 给项目做证据留存：在博主发布后立刻抓一份原始素材（防止博主删帖）

## 前置条件

- `yt-dlp` 已安装（后端 docker 镜像里已装）
- `ffmpeg` 已安装（全量模式需要抽音频时用）
- 有效的 `COOKIES` 环境变量（从公司共享 cookie 池自动注入），没有的话简单视频也能下载，但触发反爬概率大

## 使用方式

### CLI

```bash
python scripts/download_xhs.py \
    --url "https://www.xiaohongshu.com/explore/xxx" \
    --output out.json \
    --output-dir ./work \
    --mode full
```

参数：

| 参数 | 含义 | 可选值 / 默认 |
|---|---|---|
| `--url` | 视频 / 图文链接 | 必填 |
| `--output` | skill 机器可读输出 (out.json) | 必填 |
| `--output-dir` | 资源落地目录 | 默认 `./<output 父目录>/files/` |
| `--mode` | 产出模式 | `basic`（只下视频，默认） / `full`（视频+音频+字幕+脚本） / `summary`（full + 写 meta 供 LLM 汇总）|
| `--quality` | 视频清晰度 | `best`（默认）/ `1080p` / `720p` / `480p` |
| `--audio-only` | 只下音频 mp3 | flag，默认 false |
| `--list-formats` | 只打印可用格式，不下载 | flag |

### 输出 out.json

```json
{
  "ok": true,
  "url": "https://www.xiaohongshu.com/explore/xxx",
  "mode": "full",
  "title": "爆款视频标题",
  "uploader": "博主昵称",
  "duration": 58,
  "files": {
    "video": "./work/video.mp4",
    "audio": "./work/audio.mp3",
    "subtitle": "./work/subtitle.vtt",
    "transcript": "./work/transcript.txt",
    "meta": "./work/.meta.json"
  },
  "transcript_preview": "视频的前 500 字纯文本……",
  "errorType": null
}
```

失败时：

```json
{
  "ok": false,
  "errorType": "yt_dlp_failed | cookie_invalid | url_invalid | ffmpeg_missing",
  "error": "人类可读原因",
  "files": {}
}
```

## 下游

- `full` / `summary` 模式后，agent 可 `read_file(transcript.txt)` → LLM 拆解爆款结构
- 配合 `xhs-note-detail` 拿到笔记元数据，做"博主内容调性档案"
- 长期存档：产出文件可由 `asset_upsert` tool 入项目资产库（Phase 4A 未来工作）

## 成本 / 风险

- 单条 60 秒视频：视频下载 5~15 秒，字幕回退若走 whisper 另加 30~120 秒
- 反爬风险：短时间内大量下载会被限。建议间隔 10s+
- 字幕策略是三级：手写字幕 → yt-dlp 自动字幕 → Whisper（本实现里 Whisper 为可选，未装则跳过）

## 来源

本 skill 基于 openclaw 备份 `workspace/xiaohongshu-downloader/` 改造：移除 macOS 硬编码路径、加入 COOKIES env 支持、产出标准 out.json、沙箱化输出目录。
