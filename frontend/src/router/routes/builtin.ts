import type { RouteRecordRaw } from 'vue-router';
import type { CustomRoute } from '@elegant-router/types';
import { layouts, views } from '../elegant/imports';
import { getRoutePath, transformElegantRoutesToVueRoutes } from '../elegant/transform';

export const ROOT_ROUTE: CustomRoute = {
  name: 'root',
  path: '/',
  redirect: getRoutePath(import.meta.env.VITE_ROUTE_HOME) || '/home',
  meta: {
    title: 'root',
    constant: true
  }
};

const NOT_FOUND_ROUTE: CustomRoute = {
  name: 'not-found',
  path: '/:pathMatch(.*)*',
  component: 'layout.blank$view.404',
  meta: {
    title: 'not-found',
    constant: true
  }
};

/** builtin routes, it must be constant and setup in vue-router */
const builtinRoutes: CustomRoute[] = [ROOT_ROUTE, NOT_FOUND_ROUTE];

/**
 * 历史路径兼容：老用户浏览器书签可能停留在 `/xhs-cookies`（2026-04 前）。
 * 2026-04-21 该页被 `/data-sources` 合并取代；此处保留硬跳转，避免 404。
 *
 * 用裸 RouteRecordRaw 而不是 CustomRoute，因为 elegant-router 的 CustomRoute 类型只允许 root / not-found 两种 name。
 */
const LEGACY_REDIRECTS: RouteRecordRaw[] = [
  {
    path: '/xhs-cookies',
    redirect: () => ({ path: '/data-sources', query: { tab: 'xhs_web' } }),
    meta: { constant: true, title: 'legacy-xhs-cookies' }
  }
];

/** create builtin vue routes */
export function createBuiltinVueRoutes() {
  const generated = transformElegantRoutesToVueRoutes(builtinRoutes, layouts, views);
  return [...generated, ...LEGACY_REDIRECTS];
}
