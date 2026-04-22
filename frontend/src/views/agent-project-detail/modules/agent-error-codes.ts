/**
 * Agent 工具错误码 → 友好文案 + 可选帮助跳转的映射表。
 *
 * 后端 {@link com.yizhaoqi.smartpai.service.tool.ToolErrors} 定义错误码常量；
 * 本文件是"给终端用户看"的面向产品的说明。
 *
 * 新增错误码时，同步在后端 ToolErrors 里加常量 + 让工具用 {@code ToolResult.error(code, humanMessage)}
 * 返回；前端这里只负责"怎么呈现"（label / 帮助链接），不参与判断。
 *
 * 如果 errorCode 不在映射表里，UI 会只显示 tool_result.summary（已经是人话），不画帮助链接。
 */

export interface ErrorCodeHelp {
  /** 短标签，展示在红色徽章上。 */
  label: string;
  /** 可选的站内跳转，点击去帮助用户解决这个问题。 */
  action?: {
    text: string;
    /** route path，走 vue-router */
    to?: string;
    /** 或者外链 */
    href?: string;
  };
}

export const AGENT_ERROR_CODES: Record<string, ErrorCodeHelp> = {
  config_missing: {
    label: '配置缺失',
    action: { text: '去数据源页检查', to: '/data-sources' }
  },
  cookie_invalid: {
    label: 'Cookie 失效',
    action: { text: '重新扫码登录', to: '/data-sources' }
  },
  no_target: {
    label: '未录入',
    action: { text: '去录入凭证', to: '/data-sources' }
  },
  wrong_platform: {
    label: '平台不匹配'
  },
  missing_refresh_token: {
    label: '缺 refresh_token',
    action: { text: '去数据源重新录入', to: '/data-sources' }
  },
  not_found: {
    label: '不存在'
  },
  bad_request: {
    label: '参数错误'
  },
  permission_denied: {
    label: '权限不够'
  },
  confirmation_required: {
    label: '等待确认'
  },
  rate_limit: {
    label: '被限流'
  },
  timeout: {
    label: '超时'
  },
  network: {
    label: '网络错误'
  },
  upstream_error: {
    label: '上游错误'
  },
  upstream_rejected: {
    label: '上游拒绝'
  },
  internal: {
    label: '内部错误'
  }
};

export function lookupErrorHelp(code: string | null | undefined): ErrorCodeHelp | null {
  if (!code) return null;
  return AGENT_ERROR_CODES[code] || null;
}
