<script setup lang="ts">
/**
 * 数据源中心 —— 统一管理三个平台的"抓取凭证"：
 *
 *  1. 小红书 · 网页 Cookie（Spider_XHS 通道）         → 平台值 xhs_pc / xhs_creator / xhs_pgy / xhs_qianfan
 *  2. 小红书 · 聚光 MarketingAPI（官方广告数据）       → 平台值 xhs_spotlight（OAuth2 access_token）
 *  3. 小红书 · 竞品笔记监控（xhsCompetitorNote 接入）   → 平台值 xhs_competitor（站点 URL + key）
 *
 * 三者共用后端 `/api/v1/admin/xhs-cookies`（凭证统一存加密表），区别在于 platform 值和前端表单。
 *
 * 该页面仅 ADMIN 可见（由 router meta.roles 控制）。
 */
import { computed, h, onMounted, ref } from 'vue';
import { NAlert, NTabPane, NTabs, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchXhsCookieList } from '@/service/api';
import XhsWebPanel from './modules/xhs-web-panel.vue';
import XhsSpotlightPanel from './modules/xhs-spotlight-panel.vue';
import XhsCompetitorPanel from './modules/xhs-competitor-panel.vue';

defineOptions({ name: 'DataSources' });

const activeTab = ref<Api.Xhs.DataSourceFamily>('xhs_web');

const items = ref<Api.Xhs.Cookie[]>([]);
const insecureDefault = ref(false);
const loading = ref(false);

async function reload() {
  loading.value = true;
  try {
    const { data, error } = await fetchXhsCookieList();
    if (!error && data) {
      items.value = data.items || [];
      insecureDefault.value = Boolean(data.insecureDefault);
    }
  } finally {
    loading.value = false;
  }
}

onMounted(reload);

const countBy = computed(() => {
  const map: Record<Api.Xhs.DataSourceFamily, { total: number; active: number }> = {
    xhs_web: { total: 0, active: 0 },
    xhs_spotlight: { total: 0, active: 0 },
    xhs_competitor: { total: 0, active: 0 }
  };
  for (const it of items.value) {
    const fam = familyOf(it.platform);
    map[fam].total += 1;
    if (it.status === 'ACTIVE') map[fam].active += 1;
  }
  return map;
});

function familyOf(platform: Api.Xhs.Platform): Api.Xhs.DataSourceFamily {
  if (platform === 'xhs_spotlight') return 'xhs_spotlight';
  if (platform === 'xhs_competitor') return 'xhs_competitor';
  return 'xhs_web';
}

function tabLabel(fam: Api.Xhs.DataSourceFamily, label: string) {
  const c = countBy.value[fam];
  return () =>
    h('span', { class: 'inline-flex items-center gap-1' }, [
      h('span', label),
      c.total > 0
        ? h(
            NTag,
            { size: 'tiny', bordered: false, type: c.active > 0 ? 'success' : 'warning' },
            { default: () => `${c.active}/${c.total}` }
          )
        : null
    ]);
}
</script>

<template>
  <div class="flex-col-stretch h-full gap-16px overflow-hidden">
    <NCard
      :bordered="false"
      size="small"
      class="shrink-0 card-wrapper"
      content-style="padding: 16px 20px"
    >
      <div class="flex items-center justify-between gap-3">
        <div class="flex-col gap-2px">
          <div class="flex items-center gap-2">
            <span class="text-18px font-semibold">数据源中心</span>
            <NTag size="small" type="info" :bordered="false">admin only</NTag>
          </div>
          <span class="text-13px text-stone-500">
            博主数据会基于这里的凭证定期刷新；三种凭证并存，按需配置。新增/修改后立即生效，加密落库。
          </span>
        </div>
        <div class="flex items-center gap-2 text-xs text-stone-500">
          <SvgIcon icon="solar:refresh-circle-bold-duotone" />
          <span>共 {{ items.length }} 条凭证</span>
          <a class="cursor-pointer text-primary-500" @click="reload">刷新</a>
        </div>
      </div>
    </NCard>

    <NAlert
      v-if="insecureDefault"
      type="warning"
      :show-icon="true"
      class="shrink-0"
      title="后端仍在使用默认的 cookie 加密密钥"
    >
      生产部署前请在后端 `.env` 中配置至少 32 字节的随机字符串
      <code class="font-mono">XHS_COOKIE_SECRET</code>，否则凭证在数据库里几乎等同于明文。
    </NAlert>

    <NCard
      :bordered="false"
      size="small"
      class="h-0 flex-auto overflow-hidden card-wrapper"
      content-style="height: 100%; padding: 0;"
    >
      <NTabs
        v-model:value="activeTab"
        type="line"
        animated
        size="large"
        pane-style="padding: 16px 20px 0; height: 100%;"
        tab-style="padding: 10px 18px;"
        class="h-full"
        pane-class="h-full"
      >
        <NTabPane name="xhs_web" :tab="tabLabel('xhs_web', '小红书 · 网页 Cookie')" display-directive="show">
          <XhsWebPanel :items="items.filter(i => familyOf(i.platform) === 'xhs_web')" @changed="reload" />
        </NTabPane>
        <NTabPane name="xhs_spotlight" :tab="tabLabel('xhs_spotlight', '聚光广告 OAuth')" display-directive="if">
          <XhsSpotlightPanel :items="items.filter(i => i.platform === 'xhs_spotlight')" @changed="reload" />
        </NTabPane>
        <NTabPane name="xhs_competitor" :tab="tabLabel('xhs_competitor', '竞品笔记监控')" display-directive="if">
          <XhsCompetitorPanel :items="items.filter(i => i.platform === 'xhs_competitor')" @changed="reload" />
        </NTabPane>
      </NTabs>
    </NCard>
  </div>
</template>

<style scoped></style>
