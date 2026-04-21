package com.yizhaoqi.smartpai.service.tool;

/**
 * 工具执行过程中的进度事件。由 {@link ToolContext#emitProgress} 推，Runtime 转发给 WS。
 * type 约定值：
 *  - "log"        纯文本日志
 *  - "partial"    增量的部分结果（例如爬虫每抓到一条就推一条）
 *  - "data"       任意结构化数据
 */
public record ToolProgress(String toolUseId, String type, String message, Object data) {
}
