<script setup lang="ts">
/**
 * 数据源中心 —— 当前架构下只剩两类数据源凭证：
 *
 *  1. 蒲公英 (PGY) · Cookie 池（仅 platform=xhs_pgy） → 用于 KOL 列表 / 粉丝画像 / 报价等品牌侧能力
 *  2. 聚光 (Spotlight) · MarketingAPI OAuth         → 关键词推荐、人群预估、计划/单元/报表
 *  3. TikHub · 公开数据通道                          → 用户搜索 / 用户笔记 / 笔记详情 / 视频无水印 / 评论 / 热搜热榜
 *
 * 老的 Spider_XHS Cookie（xhs_pc / xhs_creator / xhs_qianfan）、千瓜接口、竞品笔记监控、
 * 浏览器自动化抓取已统一下线，公开数据全部走 TikHub。
 *
 * 该页面仅 ADMIN 可见（由 router meta.roles 控制）。
 */
import { computed, h, onMounted, ref } from 'vue';
import { NAlert, NTabPane, NTabs, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchXhsCookieList } from '@/service/api';
import XhsWebPanel from './modules/xhs-web-panel.vue';
import XhsSpotlightPanel from './modules/xhs-spotlight-panel.vue';
import XhsTikhubPanel from './modules/xhs-tikhub-panel.vue';

defineOptions({ name: 'DataSources' });

const activeTab = ref<'xhs_pgy' | 'xhs_spotlight' | 'xhs_tikhub'>('xhs_tikhub');

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

type Family = 'xhs_pgy' | 'xhs_spotlight' | 'xhs_tikhub';

const countBy = computed(() => {
  const map: Record<Family, { total: number; active: number }> = {
    xhs_pgy: { total: 0, active: 0 },
    xhs_spotlight: { total: 0, active: 0 },
    xhs_tikhub: { total: 1, active: 1 }
  };
  for (const it of items.value) {
    const fam = familyOf(it.platform);
    if (fam === null) continue;
    map[fam].total += 1;
    if (it.status === 'ACTIVE') map[fam].active += 1;
  }
  return map;
});

function familyOf(platform: Api.Xhs.Platform): Family | null {
  if (platform === 'xhs_spotlight') return 'xhs_spotlight';
  if (platform === 'xhs_pgy') return 'xhs_pgy';
  return null;
}

function tabLabel(fam: Family, label: string) {
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
        <NTabPane name="xhs_tikhub" :tab="tabLabel('xhs_tikhub', 'TikHub · 公开数据')" display-directive="show">
          <XhsTikhubPanel />
        </NTabPane>
        <NTabPane name="xhs_pgy" :tab="tabLabel('xhs_pgy', '蒲公英 · Cookie')" display-directive="if">
          <XhsWebPanel :items="items.filter(i => i.platform === 'xhs_pgy')" @changed="reload" />
        </NTabPane>
        <NTabPane name="xhs_spotlight" :tab="tabLabel('xhs_spotlight', '聚光 · MarketingAPI')" display-directive="if">
          <XhsSpotlightPanel :items="items.filter(i => i.platform === 'xhs_spotlight')" @changed="reload" />
        </NTabPane>
      </NTabs>
    </NCard>
  </div>
</template>

<style scoped></style>
