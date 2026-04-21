import { request } from '../request';

export function fetchProjectList() {
  return request<Api.Project.Item[]>({ url: '/agent/projects' });
}

export function fetchProjectDetail(id: number) {
  return request<Api.Project.Item>({ url: `/agent/projects/${id}` });
}

export function fetchProjectCreate(data: Api.Project.UpsertPayload) {
  return request<Api.Project.Item>({
    url: '/agent/projects',
    method: 'post',
    data
  });
}

export function fetchProjectCreateFromTemplate(templateCode: string, name?: string) {
  return request<Api.Project.Item>({
    url: '/agent/projects/from-template',
    method: 'post',
    data: { templateCode, name }
  });
}

export function fetchProjectUpdate(id: number, data: Api.Project.UpsertPayload) {
  return request<Api.Project.Item>({
    url: `/agent/projects/${id}`,
    method: 'put',
    data
  });
}

export function fetchProjectArchive(id: number) {
  return request({ url: `/agent/projects/${id}`, method: 'delete' });
}

export function fetchProjectTemplates() {
  return request<Api.Project.Template[]>({ url: '/agent/projects/templates' });
}
