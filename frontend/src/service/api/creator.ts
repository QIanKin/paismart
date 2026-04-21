import { getServiceBaseURL } from '@/utils/service';
import { request } from '../request';

/** ---------------- Creator (person) ---------------- */

export function fetchCreatorList(params: Api.Creator.SearchPersonParams) {
  return request<Api.Creator.PageResult<Api.Creator.Person>>({
    url: '/creators',
    params
  });
}

export function fetchCreatorDetail(id: number) {
  return request<Api.Creator.Person>({ url: `/creators/${id}` });
}

export function fetchCreatorUpsert(data: Partial<Api.Creator.Person>, id?: number) {
  return request<Api.Creator.Person>({
    url: id ? `/creators/${id}` : '/creators',
    method: id ? 'put' : 'post',
    data
  });
}

export function fetchCreatorDelete(id: number) {
  return request({ url: `/creators/${id}`, method: 'delete' });
}

/** ---------------- Accounts ---------------- */

export function fetchAccountList(params: Api.Creator.SearchAccountParams) {
  return request<Api.Creator.PageResult<Api.Creator.Account>>({
    url: '/creators/accounts',
    params
  });
}

export function fetchAccountDetail(id: number, includePosts = true) {
  return request<Api.Creator.AccountDetail>({
    url: `/creators/accounts/${id}`,
    params: { includePosts }
  });
}

export function fetchAccountUpsert(data: Partial<Api.Creator.Account>) {
  return request<Api.Creator.Account>({
    url: '/creators/accounts',
    method: 'post',
    data
  });
}

export function fetchAccountSnapshot(id: number, data: Partial<Api.Creator.Snapshot>) {
  return request<Api.Creator.Snapshot>({
    url: `/creators/accounts/${id}/snapshots`,
    method: 'post',
    data
  });
}

/**
 * 分页拉某账号的最近笔记；ttlHours + refresh 决定是否在拉之前先触发一次 xhs 刷新。<br>
 * - stale=true 表示缓存过期（下次可 refresh=true 强拉）<br>
 * - mostRecentSnapshotAt 是这一批里最近一次 metrics 采样时间
 */
export function fetchAccountPosts(
  id: number,
  params?: { page?: number; size?: number; ttlHours?: number; refresh?: boolean }
) {
  return request<{
    total: number;
    page: number;
    size: number;
    items: Api.Creator.Post[];
    mostRecentSnapshotAt?: string | null;
    stale?: boolean;
  }>({
    url: `/creators/accounts/${id}/posts`,
    params: {
      page: params?.page ?? 0,
      size: params?.size ?? 20,
      ttlHours: params?.ttlHours ?? 24,
      refresh: params?.refresh ?? false
    }
  });
}

export function fetchAccountBatchPosts(id: number, posts: Partial<Api.Creator.Post>[]) {
  return request<Api.Creator.BatchPostUpsertResult>({
    url: `/creators/accounts/${id}/posts:batch`,
    method: 'post',
    data: { posts }
  });
}

/** 一键刷新 xhs 博主最近 N 条笔记（后端走 xhs-user-notes skill + cookie 池）。 */
export function fetchRefreshXhsAccount(id: number, params?: { limit?: number; dryRun?: boolean }) {
  return request<Api.Creator.XhsRefreshResult>({
    url: `/creators/accounts/${id}/refresh:xhs`,
    method: 'post',
    data: params ?? {}
  });
}

/** ---------------- Custom fields ---------------- */

export function fetchCustomFields(entityType: Api.Creator.CustomFieldEntity = 'account') {
  return request<Api.Creator.CustomField[]>({
    url: '/creators/custom-fields',
    params: { entityType }
  });
}

export function fetchCustomFieldUpsert(data: Api.Creator.CustomField) {
  return request<Api.Creator.CustomField>({
    url: '/creators/custom-fields',
    method: 'post',
    data
  });
}

export function fetchCustomFieldDelete(id: number) {
  return request({ url: `/creators/custom-fields/${id}`, method: 'delete' });
}

/** ---------------- xlsx export ---------------- */

/** 直接让浏览器打开下载链接，sso token 通过 a 标签 + Authorization 不好走，这里保留 query 版本。 */
export function buildCreatorExportUrl(
  params: Api.Creator.SearchAccountParams & {
    maxRows?: number;
    fields?: string[];
    includeCustomFields?: boolean;
    /** 多选导出：传了就忽略筛选条件，按 id 批量导 */
    accountIds?: number[];
  }
) {
  const { baseURL } = getServiceBaseURL(import.meta.env, false);
  const qs = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v === null || v === undefined) return;
    if (Array.isArray(v)) v.forEach(x => qs.append(k, String(x)));
    else qs.append(k, String(v));
  });
  return `${baseURL}/creators/export.xlsx?${qs.toString()}`;
}
