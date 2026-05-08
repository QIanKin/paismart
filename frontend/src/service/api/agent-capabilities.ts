import { request } from '../request';

/**
 * Agent 能力中心相关 REST API。
 * 后端入口：
 *  - {@code GET /api/v1/agent/tools/catalog}    工具按域分组
 *  - {@code GET /api/v1/agent/skills}            可见 skill 列表
 *  - {@code GET /api/v1/agent/skills/{name}}     skill 详情（含原文 SKILL.md）
 *  - {@code POST /api/v1/agent/skills/reload}    手动触发磁盘重新加载
 *  - {@code PUT /api/v1/agent/skills/{id}/enabled}  启用 / 禁用
 *  - {@code PUT /api/v1/agent/skills/{id}/source}   覆盖写回 SKILL.md
 */

export interface ToolCatalogEntry {
  name: string;
  userFacingName: string;
  description: string;
  readOnly: boolean;
  destructive: boolean;
}

export interface ToolCatalogGroup {
  id: string;
  name: string;
  description?: string;
  count: number;
  tools: ToolCatalogEntry[];
}

export interface ToolCatalog {
  total: number;
  groups: ToolCatalogGroup[];
}

export function fetchToolsCatalog() {
  return request<ToolCatalog>({ url: '/agent/tools/catalog' });
}

export interface SkillManifest {
  id?: number;
  name: string;
  description?: string;
  version?: string;
  homepage?: string | null;
  scripts?: string[];
  requiredBins?: string[];
  ownerOrgTag?: string | null;
  source?: 'BUILTIN' | 'LOCAL' | 'INSTALLED' | string | null;
  enabled?: boolean;
  rootPath?: string | null;
  bodyHash?: string | null;
}

export interface SkillDetail extends SkillManifest {
  /** SKILL.md 去 front-matter 后的 body */
  instructions?: string | null;
  /** SKILL.md 完整原文（含 front-matter）；前端编辑用 */
  rawMarkdown?: string | null;
}

export function fetchSkillsList() {
  return request<SkillManifest[]>({ url: '/agent/skills' });
}

export function fetchSkillDetail(name: string) {
  return request<SkillDetail>({ url: `/agent/skills/${encodeURIComponent(name)}` });
}

export function fetchSkillsReload() {
  return request<{
    scanned: number;
    added: number;
    updated: number;
    skipped: number;
    disabled: number;
    errors: number;
    activeCount: number;
  }>({ url: '/agent/skills/reload', method: 'post' });
}

export function fetchSkillSetEnabled(id: number, enabled: boolean) {
  return request<{ id: number; name: string; enabled: boolean }>({
    url: `/agent/skills/${id}/enabled`,
    method: 'put',
    data: { enabled }
  });
}

/** 覆盖写回 SKILL.md（含 front-matter）。BUILTIN 来源会被后端拒绝。 */
export function fetchSkillEditSource(id: number, content: string) {
  return request<{ id: number; name: string; reload: any }>({
    url: `/agent/skills/${id}/source`,
    method: 'put',
    data: { content }
  });
}

export interface FeatureFlagView {
  key: string;
  label: string;
  description: string;
  /** 综合 DB override 与 yml 默认得到的当前值（即 LLM manifest 阶段会用的值） */
  enabled: boolean;
  /** 是否在 DB 里有显式覆盖；false 表示当前走 yml 默认 */
  overridden: boolean;
  /** application.yml + .env 给出的默认值 */
  ymlDefault: boolean;
  /** 与该 flag 关联的工具名前缀；前端展示用，可让用户预知"关掉会影响哪些工具" */
  toolPrefixes: string[];
}

export function fetchFeatureFlagsList() {
  return request<FeatureFlagView[]>({ url: '/agent/feature-flags' });
}

/**
 * 切换某个 feature flag。
 * - 传 boolean → 写 DB override；
 * - 传 null → 清除 override，回退 yml 默认。
 */
export function fetchFeatureFlagSet(key: string, enabled: boolean | null) {
  return request<FeatureFlagView[]>({
    url: `/agent/feature-flags/${encodeURIComponent(key)}`,
    method: 'put',
    data: { enabled }
  });
}
