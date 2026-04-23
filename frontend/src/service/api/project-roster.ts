import { request } from '../request';

/**
 * 项目博主名册（对应后端 /api/v1/agent/projects/{id}/creators）。
 *
 * 名册 = 一个项目正在合作 / 备选的博主集合，带阶段（CANDIDATE/SHORTLISTED/LOCKED/...）。
 * UI 的「博主名册 Tab」和 ALLOCATION 会话 AI 都走这套接口。
 */

export function fetchProjectRoster(projectId: number) {
  return request<Api.Project.RosterEntry[]>({
    url: `/agent/projects/${projectId}/creators`
  });
}

export function fetchProjectRosterAdd(
  projectId: number,
  data: { creatorId: number; stage?: Api.Project.RosterStage | null } & Api.Project.RosterUpsertPayload
) {
  return request<Api.Project.RosterEntry>({
    url: `/agent/projects/${projectId}/creators`,
    method: 'post',
    data
  });
}

/**
 * 批量加入名册。可以按"人 id"（creatorIds）或"账号 id"（accountIds）其一入库；
 * 走账号时，如果 account 没绑定 Creator（人），后端会自动造一个并绑定。
 */
export function fetchProjectRosterAddBatch(
  projectId: number,
  ids: { creatorIds?: number[]; accountIds?: number[] },
  stage: Api.Project.RosterStage = 'SHORTLISTED'
) {
  return request<Api.Project.RosterEntry[]>({
    url: `/agent/projects/${projectId}/creators/batch`,
    method: 'post',
    data: { ...ids, stage }
  });
}

export function fetchProjectRosterUpdate(
  projectId: number,
  entryId: number,
  data: Api.Project.RosterUpsertPayload
) {
  return request<Api.Project.RosterEntry>({
    url: `/agent/projects/${projectId}/creators/${entryId}`,
    method: 'put',
    data
  });
}

export function fetchProjectRosterStage(
  projectId: number,
  entryId: number,
  stage: Api.Project.RosterStage
) {
  return request<Api.Project.RosterEntry>({
    url: `/agent/projects/${projectId}/creators/${entryId}/stage`,
    method: 'put',
    data: { stage }
  });
}

export function fetchProjectRosterRemove(projectId: number, entryId: number) {
  return request({
    url: `/agent/projects/${projectId}/creators/${entryId}`,
    method: 'delete'
  });
}
