# KOL 分析 / 选题大纲样本库

MCN 场景下 agent 产出项目报告 / 博主画像 / 选题大纲时可引用的 few-shot 样例。
这些来自实际跑过的外联项目（Tory Burch · 香粉粉丝场景），可以直接作为 `use_skill` 时的风格模板。

## 样本清单

| 文件 | 类型 | 用途 |
|---|---|---|
| `KOL_Analysis_Maggie_Top3.md` | 博主 Top3 画像 Markdown | 一个博主的核心画像 + 内容调性提炼 |
| `maggie_kol_outline_xiangfen_fan.html` | 选题大纲 v1 HTML | 针对指定 KOL 的内容选题方向 |
| `maggie_kol_outline_xiangfen_fan_v2.html` | 选题大纲 v2 HTML | 迭代版（更精细的结构化输出） |

## 怎么让 agent 引用

两种方式：

### 1. 直接走 read_file

Agent 收到"给 xxx 博主做一份画像 / 选题大纲"需求时，可以先 `read_file` 这里任意一份文件作为参考，再套写目标博主的数据。

### 2. 包进 prompt 前缀（推荐）

在项目（AgentProject）的 `systemPrompt` 里附一句：

```
当输出博主画像 / 选题大纲时，请参照 /skills-bundled/_shared/kol-report-samples/ 下的样例风格。
```

让 Agent 自己在需要时 `read_file` 取样。

## 样本采集时间

2026 年 4 月，基于 openclaw 外联自动化实际跑出的产出物。
