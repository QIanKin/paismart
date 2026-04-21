import type { RouteMeta } from 'vue-router';
import ElegantVueRouter from '@elegant-router/vue/vite';
import type { RouteKey } from '@elegant-router/types';

export function setupElegantRouter() {
  return ElegantVueRouter({
    layouts: {
      base: 'src/layouts/base-layout/index.vue',
      blank: 'src/layouts/blank-layout/index.vue'
    },
    routePathTransformer(routeName, routePath) {
      const key = routeName as RouteKey;

      if (key === 'login') {
        const modules: UnionKey.LoginModule[] = ['pwd-login', 'code-login', 'register', 'reset-pwd', 'bind-wechat'];

        const moduleReg = modules.join('|');

        return `/login/:module(${moduleReg})?`;
      }

      // 项目详情页是「项目列表」的深钻页：路径形如 /agent-projects/:id。
      // 按约定 views/agent-project-detail/index.vue 会被生成为 /agent-project-detail，
      // 这里强制重写到嵌套路径，保持和菜单项「我的项目」同级高亮。
      if (key === 'agent-project-detail') {
        return '/agent-projects/:id';
      }

      return routePath;
    },
    onRouteMetaGen(routeName) {
      const key = routeName as RouteKey;

      const constantRoutes: RouteKey[] = ['login', '403', '404', '500'];

      const meta: Partial<RouteMeta> = {
        title: key,
        i18nKey: `route.${key}` as App.I18n.I18nKey
      };

      if (constantRoutes.includes(key)) {
        meta.constant = true;
      }

      // 详情页不进菜单，但点进去仍然应该让「我的项目」这一栏保持高亮。
      if (key === 'agent-project-detail') {
        meta.hideInMenu = true;
        meta.activeMenu = 'agent-projects';
      }

      return meta;
    }
  });
}
