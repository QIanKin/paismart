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
