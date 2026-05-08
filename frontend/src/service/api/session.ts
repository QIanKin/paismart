import { request } from '../request';

/**
 * Agent 会话 REST API（对应后端 /api/v1/agent/sessions）。
 *
 * 设计：每个会话属于某个项目（projectId），是"一个博主方案沟通"这样的 业务维度。WebSocket 发消息时把 sessionId/projectId 塞进 payload，
 * 后端就知道消息落在哪条会话里（以及对应的上下文/记忆）。
 */

export function fetchSessionList(
  projectId?: number,
  opts?: { sessionType?: Api.Session.SessionType; creatorId?: number | null }
) {
  const params: Record<string, unknown> = {};
  if (projectId) params.projectId = projectId;
  if (opts?.sessionType) params.sessionType = opts.sessionType;
  if (opts?.creatorId) params.creatorId = opts.creatorId;
  return request<Api.Session.Item[]>({
    url: '/agent/sessions',
    params: Object.keys(params).length ? params : undefined
  });
}

/**
 * 创建会话。
 * - 旧签名 `fetchSessionCreate(projectId, title)` 仍兼容；
 * - 也支持传 CreateParams，带 sessionType / creatorId（博主绑定会话必须带 creatorId）。
 */
export function fetchSessionCreate(
  projectIdOrParams: number | Api.Session.CreateParams,
  title?: string
) {
  const payload: Record<string, unknown> =
    typeof projectIdOrParams === 'number'
      ? { projectId: projectIdOrParams, title: title ?? null }
      : {
          projectId: projectIdOrParams.projectId,
          title: projectIdOrParams.title ?? null,
          sessionType: projectIdOrParams.sessionType ?? 'GENERAL',
          creatorId: projectIdOrParams.creatorId ?? null
        };
  return request<Api.Session.Item>({
    url: '/agent/sessions',
    method: 'post',
    data: payload
  });
}

export function fetchSessionDetail(id: number) {
  return request<Api.Session.Item>({ url: `/agent/sessions/${id}` });
}

export function fetchSessionMessages(id: number) {
  return request<Api.Session.Message[]>({ url: `/agent/sessions/${id}/messages` });
}

/**
 * 拉当前会话「正在进行中」的 turn 快照（partial assistant + 进行中工具卡片）。
 *
 * <p>用于「用户切走或刷新后再回来」场景：先 {@link fetchSessionMessages} 拉历史（已 appendTurn 的内容），
 * 再调本接口把仍在生成中的部分拼到列表尾。后续 chunk 通过既有 WS 长连接继续推。
 *
 * <p>无活跃 turn 时后端返回 {@code data: null}。
 */
export function fetchSessionLive(id: number) {
  return request<Api.Session.LiveSnapshot | null>({ url: `/agent/sessions/${id}/live` });
}

export function fetchSessionRename(id: number, title: string) {
  return request<Api.Session.Item>({
    url: `/agent/sessions/${id}`,
    method: 'put',
    data: { title }
  });
}

export function fetchSessionArchive(id: number) {
  return request({ url: `/agent/sessions/${id}`, method: 'delete' });
}
