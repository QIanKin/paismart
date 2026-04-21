import { request } from '../request';

/**
 * 管理员 —— 小红书/蒲公英 Cookie 池管理
 *
 * 对应后端 `/api/v1/admin/xhs-cookies`，仅 ADMIN 角色可见。 Cookie 在后端以 AES/GCM 加密存储，前端只能看到 `cookiePreview` 的明文前缀。
 */
export function fetchXhsCookieList() {
  return request<Api.Xhs.CookieListResponse>({
    url: '/admin/xhs-cookies'
  });
}

export function fetchXhsCookieCreate(data: Api.Xhs.CookieCreatePayload) {
  return request<Api.Xhs.Cookie>({
    url: '/admin/xhs-cookies',
    method: 'post',
    data
  });
}

export function fetchXhsCookieUpdate(id: number, data: Api.Xhs.CookieUpdatePayload) {
  return request<Api.Xhs.Cookie>({
    url: `/admin/xhs-cookies/${id}`,
    method: 'put',
    data
  });
}

export function fetchXhsCookieDelete(id: number) {
  return request<{ deleted: boolean }>({
    url: `/admin/xhs-cookies/${id}`,
    method: 'delete'
  });
}

/** 预检：把一串 Cookie 文本发给后端，回返 detectedKeys / missingRequired。不落库、不加密。 */
export function fetchXhsCookieValidate(cookie: string) {
  return request<Api.Xhs.CookieValidateResponse>({
    url: '/admin/xhs-cookies/validate',
    method: 'post',
    data: { cookie }
  });
}

/**
 * 连通性测试：用该 cookie 去打一次平台轻量 API（如 /user/me），验活不降权。
 * 测试失败不会把 cookie 标 EXPIRED，避免"测试本身"污染线上状态。
 */
export function fetchXhsCookiePing(id: number) {
  return request<Api.Xhs.CookiePingResult>({
    url: `/admin/xhs-cookies/${id}/ping`,
    method: 'post'
  });
}

// ---------- 扫码登录 ----------

/**
 * 创建一次扫码登录会话。
 * 拿到 sessionId 后应立刻通过 `/ws/xhs-login/{token}?session={sessionId}` 订阅事件。
 *
 * @param platforms 留空则取后端默认（xhs_pc,xhs_creator,xhs_pgy,xhs_qianfan）
 */
export function fetchXhsLoginStart(platforms?: Api.Xhs.Platform[]) {
  return request<Api.Xhs.LoginStartResponse>({
    url: '/admin/xhs-cookies/qr-login',
    method: 'post',
    data: platforms && platforms.length ? { platforms } : {}
  });
}

/** 查询当前会话的最新状态（供断线重连 / 轮询兜底）。 */
export function fetchXhsLoginStatus(sessionId: string) {
  return request<Api.Xhs.LoginStatusResponse>({
    url: `/admin/xhs-cookies/qr-login/${sessionId}`
  });
}

/** 业务员主动取消，后端会杀掉 node 子进程并把会话标 CANCELLED。 */
export function fetchXhsLoginCancel(sessionId: string) {
  return request<{ cancelled: boolean }>({
    url: `/admin/xhs-cookies/qr-login/${sessionId}/cancel`,
    method: 'post'
  });
}
