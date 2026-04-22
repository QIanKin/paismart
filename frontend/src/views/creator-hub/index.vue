<script setup lang="tsx">
import { computed, onMounted, reactive, ref } from 'vue';
import {
  NAvatar,
  NButton,
  NDataTable,
  NFlex,
  NInput,
  NInputNumber,
  NPopconfirm,
  NSelect,
  NSwitch,
  NTag
} from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  buildCreatorExportUrl,
  fetchAccountDetail,
  fetchAccountList,
  fetchAccountUpsert,
  fetchCustomFields,
  fetchRefreshXhsAccount,
  fetchXhsCookieList
} from '@/service/api';
import { getAuthorization } from '@/service/request/shared';
import CreatorAccountDrawer from './modules/creator-account-drawer.vue';
import CustomFieldDrawer from './modules/custom-field-drawer.vue';
import CreatorUpsertDialog from './modules/creator-upsert-dialog.vue';

defineOptions({ name: 'CreatorHub' });

const appStore = useAppStore();

const loading = ref(false);
const rows = ref<Api.Creator.Account[]>([]);
const total = ref(0);

const xhsCookieStats = ref<{ active: number; total: number; hasInsecure: boolean }>({
  active: 0,
  total: 0,
  hasInsecure: false
});
const lastLoadedAt = ref<string>('');

const filters = reactive<Api.Creator.SearchAccountParams>({
  platform: null,
  keyword: null,
  categoryMain: null,
  followersMin: null,
  followersMax: null,
  verifiedOnly: null,
  tagContains: null,
  page: 0,
  size: 20,
  sort: 'followers:desc'
});

const platformOptions = [
  { label: '全部平台', value: null as any },
  { label: '小红书', value: 'xhs' },
  { label: '抖音', value: 'douyin' },
  { label: '快手', value: 'kuaishou' },
  { label: 'B 站', value: 'bilibili' },
  { label: '微博', value: 'weibo' },
  { label: 'YouTube', value: 'youtube' },
  { label: 'Instagram', value: 'instagram' },
  { label: 'TikTok', value: 'tiktok' }
];

const sortOptions = [
  { label: '粉丝 · 高 → 低', value: 'followers:desc' },
  { label: '粉丝 · 低 → 高', value: 'followers:asc' },
  { label: '近期更新优先', value: 'updatedAt:desc' },
  { label: '互动率 · 高 → 低', value: 'engagementRate:desc' },
  { label: '最新入库', value: 'id:desc' }
];

const customFields = ref<Api.Creator.CustomField[]>([]);

async function reloadCustomFields() {
  const { data } = await fetchCustomFields('account');
  customFields.value = data ?? [];
}

async function load() {
  loading.value = true;
  try {
    const { data } = await fetchAccountList(filters);
    if (data) {
      rows.value = data.items ?? [];
      total.value = data.total ?? 0;
      lastLoadedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    }
  } finally {
    loading.value = false;
  }
}

async function reloadCookieStats() {
  try {
    const { data, error } = await fetchXhsCookieList();
    if (!error && data) {
      const items = data.items || [];
      xhsCookieStats.value = {
        active: items.filter(i => i.status === 'ACTIVE').length,
        total: items.length,
        hasInsecure: Boolean(data.insecureDefault)
      };
    }
  } catch {
    // cookie 列表接口是 ADMIN 限定的，普通用户拿不到 - 这里容错即可
  }
}

/** 粉丝量平均值，用来给 stats 做一个可读的"体量中位数"提示。 */
const avgFollowersLabel = computed(() => {
  if (!rows.value.length) return '—';
  const sum = rows.value.reduce((acc, r) => acc + (r.followers || 0), 0);
  const avg = Math.round(sum / rows.value.length);
  return fmtNumber(avg);
});

const verifiedCount = computed(() => rows.value.filter(r => r.verified).length);

function resetFilters() {
  filters.platform = null;
  filters.keyword = null;
  filters.categoryMain = null;
  filters.followersMin = null;
  filters.followersMax = null;
  filters.verifiedOnly = null;
  filters.tagContains = null;
  filters.page = 0;
  filters.sort = 'followers:desc';
  load();
}

function onSearch() {
  filters.page = 0;
  load();
}

const pagination = computed(() => ({
  page: (filters.page ?? 0) + 1,
  pageSize: filters.size ?? 20,
  itemCount: total.value,
  pageSizes: [10, 20, 50, 100],
  showSizePicker: true,
  onChange: (p: number) => {
    filters.page = p - 1;
    load();
  },
  onUpdatePageSize: (s: number) => {
    filters.size = s;
    filters.page = 0;
    load();
  }
}));

function fmtNumber(v?: number | null) {
  if (v === null || v === undefined) return '-';
  if (v >= 10000) return `${(v / 10000).toFixed(1)}w`;
  return String(v);
}

function fmtPercent(v?: number | null) {
  if (v === null || v === undefined) return '-';
  return `${(v * 100).toFixed(2)}%`;
}

function parseTags(raw?: string | null): string[] {
  if (!raw) return [];
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.map(x => String(x)) : [];
  } catch {
    return String(raw)
      .split(/[,，]/)
      .map(x => x.trim())
      .filter(Boolean);
  }
}

// 详情抽屉
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailData = ref<Api.Creator.AccountDetail | null>(null);

async function openDetail(id: number) {
  detailVisible.value = true;
  detailLoading.value = true;
  detailData.value = null;
  try {
    const { data } = await fetchAccountDetail(id, true);
    detailData.value = data ?? null;
  } finally {
    detailLoading.value = false;
  }
}

// 新增/编辑账号
const upsertVisible = ref(false);
const editingAccount = ref<Partial<Api.Creator.Account> | null>(null);

function openCreate() {
  editingAccount.value = { platform: 'xhs' };
  upsertVisible.value = true;
}

function openEdit(row: Api.Creator.Account) {
  editingAccount.value = { ...row };
  upsertVisible.value = true;
}

async function handleUpsertSubmit(payload: Partial<Api.Creator.Account>) {
  const { error } = await fetchAccountUpsert(payload);
  if (!error) {
    upsertVisible.value = false;
    window.$message?.success('保存成功');
    load();
  }
}

// 自定义字段抽屉
const customFieldVisible = ref(false);

// 小红书一键刷新
const refreshingIds = ref<Set<number>>(new Set());
async function handleRefreshXhs(row: Api.Creator.Account) {
  if (row.platform !== 'xhs') {
    window.$message?.warning('暂不支持该平台的一键刷新');
    return;
  }
  if (!row.platformUserId) {
    window.$message?.warning('缺少 platformUserId，无法定位小红书用户');
    return;
  }
  refreshingIds.value.add(row.id);
  try {
    const { data, error } = await fetchRefreshXhsAccount(row.id, { limit: 30 });
    if (!error && data) {
      window.$message?.success(
        `已拉到 ${data.fetched} 条笔记：+${data.inserted ?? 0} / ${data.updated ?? 0} / ${data.skipped ?? 0}`
      );
      load();
    }
  } finally {
    refreshingIds.value.delete(row.id);
  }
}

// ============ 多选 ============
const checkedIds = ref<Array<string | number>>([]);
function onCheckedChange(keys: Array<string | number>) {
  checkedIds.value = keys;
}

// ============ 批量刷新 ============
const batchRefreshing = ref(false);
async function handleBatchRefresh() {
  const ids = rows.value.filter(r => checkedIds.value.includes(r.id) && r.platform === 'xhs').map(r => r.id);
  if (ids.length === 0) {
    window.$message?.warning('请先勾选至少一个 xhs 账号');
    return;
  }
  batchRefreshing.value = true;
  try {
    let ok = 0;
    let fail = 0;
    for (const id of ids) {
      const { error } = await fetchRefreshXhsAccount(id, { limit: 30 });
      if (error) fail += 1;
      else ok += 1;
    }
    window.$message?.success(`批量刷新完成：成功 ${ok}，失败 ${fail}`);
    load();
  } finally {
    batchRefreshing.value = false;
  }
}

// ============ 导出 ============
const exporting = ref(false);
async function downloadExport(params: Parameters<typeof buildCreatorExportUrl>[0], filename: string) {
  exporting.value = true;
  try {
    const url = buildCreatorExportUrl(params);
    const resp = await fetch(url, { headers: { Authorization: getAuthorization() ?? '' } });
    if (!resp.ok) {
      window.$message?.error(`导出失败：${resp.status}`);
      return;
    }
    const blob = await resp.blob();
    const a = document.createElement('a');
    const href = URL.createObjectURL(blob);
    a.href = href;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(href);
    window.$message?.success('已开始下载');
  } finally {
    exporting.value = false;
  }
}

async function handleExportSelected() {
  const ids = checkedIds.value.map(v => Number(v)).filter(v => Number.isFinite(v) && v > 0);
  if (ids.length === 0) {
    window.$message?.warning('请先勾选至少一个博主');
    return;
  }
  await downloadExport(
    { accountIds: ids, maxRows: Math.max(ids.length, 100), includeCustomFields: true },
    `creators-selected-${Date.now()}.xlsx`
  );
}

async function handleExportAll() {
  await downloadExport(
    {
      ...filters,
      page: undefined,
      size: undefined,
      maxRows: 10000,
      includeCustomFields: true
    },
    `creators-all-${Date.now()}.xlsx`
  );
}

const columns = computed<DataTableColumns<Api.Creator.Account>>(() => [
  { type: 'selection' },
  {
    key: 'platform',
    title: '平台',
    width: 84,
    render: row => (
      <NTag size="small" type="info">
        {row.platform}
      </NTag>
    )
  },
  {
    key: 'displayName',
    title: '账号',
    minWidth: 220,
    render: row => {
      const initial = (row.displayName || row.handle || '?').slice(0, 1);
      return (
        <div class="flex items-center gap-3">
          {row.avatarUrl ? (
            <img
              src={row.avatarUrl}
              referrerpolicy="no-referrer"
              alt={initial}
              class="h-9 w-9 flex-shrink-0 rounded-full b-1 b-stone-200 bg-stone-100 object-cover"
              onError={(e: Event) => {
                const img = e.target as HTMLImageElement;
                img.style.display = 'none';
                const sib = img.nextElementSibling as HTMLElement | null;
                if (sib) sib.style.display = 'flex';
              }}
            />
          ) : null}
          <NAvatar
            size={36}
            round
            style={{ display: row.avatarUrl ? 'none' : 'flex', flexShrink: 0 }}
          >
            {initial}
          </NAvatar>
          <div class="flex-col">
            <span class="font-semibold">{row.displayName || row.handle || '-'}</span>
            <span class="text-xs text-stone-500">
              @{row.handle || row.platformUserId}
              {row.verified ? (
                <NTag class="ml-1" size="tiny" type="success">
                  已认证
                </NTag>
              ) : null}
            </span>
          </div>
        </div>
      );
    }
  },
  {
    key: 'categoryMain',
    title: '赛道',
    width: 140,
    render: row => (
      <div class="flex-col text-xs">
        <span>{row.categoryMain || '-'}</span>
        {row.categorySub ? <span class="text-stone-500">{row.categorySub}</span> : null}
      </div>
    )
  },
  {
    key: 'followers',
    title: '粉丝',
    width: 100,
    render: row => <span class="font-mono">{fmtNumber(row.followers)}</span>
  },
  {
    key: 'engagementRate',
    title: '互动率',
    width: 110,
    render: row => <span class="font-mono">{fmtPercent(row.engagementRate)}</span>
  },
  {
    key: 'avgLikes',
    title: '平均点赞',
    width: 110,
    render: row => <span class="font-mono">{fmtNumber(row.avgLikes)}</span>
  },
  {
    key: 'hitRatio',
    title: '爆款率',
    width: 100,
    render: row => <span class="font-mono">{fmtPercent(row.hitRatio)}</span>
  },
  {
    key: 'platformTags',
    title: '标签',
    minWidth: 200,
    render: row => {
      const tags = parseTags(row.platformTags);
      if (tags.length === 0) return <span class="text-stone-400">-</span>;
      return (
        <div class="flex flex-wrap gap-1">
          {tags.slice(0, 4).map(t => (
            <NTag key={t} size="small" bordered={false}>
              {t}
            </NTag>
          ))}
          {tags.length > 4 ? <span class="text-xs text-stone-400">+{tags.length - 4}</span> : null}
        </div>
      );
    }
  },
  {
    key: 'updatedAt',
    title: '最近更新',
    width: 160,
    render: row => <span class="text-xs text-stone-500">{row.updatedAt?.slice(0, 19).replace('T', ' ') || '-'}</span>
  },
  {
    key: 'operate',
    title: '操作',
    width: 220,
    fixed: 'right',
    render: row => (
      <div class="flex gap-2">
        <NButton size="small" quaternary type="primary" onClick={() => openDetail(row.id)}>
          详情
        </NButton>
        <NButton size="small" quaternary onClick={() => openEdit(row)}>
          编辑
        </NButton>
        {row.platform === 'xhs' ? (
          <NButton
            size="small"
            quaternary
            type="warning"
            loading={refreshingIds.value.has(row.id)}
            onClick={() => handleRefreshXhs(row)}
          >
            刷新
          </NButton>
        ) : null}
      </div>
    )
  }
]);

onMounted(() => {
  load();
  reloadCustomFields();
  reloadCookieStats();
});
</script>

<template>
  <div class="flex-col-stretch gap-12px overflow-hidden <sm:overflow-auto">
    <!-- 顶部状态条：4 个关键指标 + xhs cookie 健康 -->
    <div class="grid shrink-0 grid-cols-2 gap-12px md:grid-cols-5">
      <NCard :bordered="false" size="small" class="card-wrapper" content-style="padding: 12px 16px">
        <div class="text-xs text-stone-500">总博主账号</div>
        <div class="text-22px font-semibold">{{ total }}</div>
        <div class="text-10px text-stone-400">含全平台 · 已过滤</div>
      </NCard>
      <NCard :bordered="false" size="small" class="card-wrapper" content-style="padding: 12px 16px">
        <div class="text-xs text-stone-500">当前页均粉丝</div>
        <div class="text-22px font-semibold">{{ avgFollowersLabel }}</div>
        <div class="text-10px text-stone-400">反映本次筛选体量</div>
      </NCard>
      <NCard :bordered="false" size="small" class="card-wrapper" content-style="padding: 12px 16px">
        <div class="text-xs text-stone-500">官方认证</div>
        <div class="text-22px font-semibold">{{ verifiedCount }}</div>
        <div class="text-10px text-stone-400">本页可见</div>
      </NCard>
      <NCard :bordered="false" size="small" class="card-wrapper" content-style="padding: 12px 16px">
        <div class="text-xs text-stone-500">小红书 Cookie 池</div>
        <div class="flex items-center gap-1 text-22px font-semibold">
          <span :class="xhsCookieStats.active > 0 ? 'text-success' : 'text-warning'">
            {{ xhsCookieStats.active }}
          </span>
          <span class="text-12px text-stone-400">/ {{ xhsCookieStats.total }}</span>
        </div>
        <div class="text-10px text-stone-400">活跃 / 总凭证（影响刷新能力）</div>
      </NCard>
      <NCard :bordered="false" size="small" class="card-wrapper" content-style="padding: 12px 16px">
        <div class="text-xs text-stone-500">数据最后加载</div>
        <div class="text-22px font-semibold">{{ lastLoadedAt || '—' }}</div>
        <div class="text-10px text-stone-400">点右上「查询」手动刷新</div>
      </NCard>
    </div>

    <NCard :bordered="false" size="small" class="h-0 flex-auto card-wrapper">
      <template #header>
        <div class="flex items-center gap-2">
          <span class="text-16px font-semibold">博主数据库</span>
          <NTag v-if="xhsCookieStats.active === 0 && xhsCookieStats.total > 0" size="tiny" type="warning" :bordered="false">
            所有 xhs cookie 失效，刷新会失败
          </NTag>
          <NTag v-else-if="xhsCookieStats.total === 0" size="tiny" :bordered="false">
            未配置 xhs cookie，去「数据源中心」添加
          </NTag>
        </div>
      </template>
      <template #header-extra>
        <NFlex :size="8" align="center">
          <span v-if="checkedIds.length" class="text-xs text-stone-500">已选 {{ checkedIds.length }}</span>
          <NButton
            v-if="checkedIds.length"
            size="small"
            :loading="batchRefreshing"
            type="warning"
            ghost
            @click="handleBatchRefresh"
          >
            批量刷新 xhs
          </NButton>
          <NButton
            v-if="checkedIds.length"
            size="small"
            :loading="exporting"
            type="primary"
            ghost
            @click="handleExportSelected"
          >
            导出所选（{{ checkedIds.length }}）
          </NButton>
          <NButton size="small" :loading="exporting" type="primary" ghost @click="handleExportAll">全量导出</NButton>
          <NButton size="small" @click="customFieldVisible = true">自定义字段</NButton>
          <NButton size="small" type="primary" @click="openCreate">新增账号</NButton>
        </NFlex>
      </template>

      <!-- 筛选（粘性工具栏） -->
      <NFlex :size="12" class="sticky top-0 z-1 flex-wrap rounded-6px bg-inherit pb-12px">
        <NSelect
          v-model:value="filters.platform"
          :options="platformOptions"
          placeholder="平台"
          clearable
          class="w-32"
        />
        <NInput v-model:value="filters.keyword" placeholder="昵称 / handle / 平台 UID" clearable class="w-64" />
        <NInput v-model:value="filters.categoryMain" placeholder="赛道(主)" clearable class="w-32" />
        <NInput v-model:value="filters.tagContains" placeholder="标签关键字" clearable class="w-32" />
        <NInputNumber v-model:value="filters.followersMin" :min="0" placeholder="粉丝 ≥" class="w-32" />
        <NInputNumber v-model:value="filters.followersMax" :min="0" placeholder="粉丝 ≤" class="w-32" />
        <NFlex :size="4" align="center">
          <span class="text-xs text-stone-500">仅认证</span>
          <NSwitch
            :value="Boolean(filters.verifiedOnly)"
            @update:value="(v: boolean) => (filters.verifiedOnly = v || null)"
          />
        </NFlex>
        <NSelect v-model:value="filters.sort" :options="sortOptions" class="w-48" />
        <NButton size="small" type="primary" @click="onSearch">查询</NButton>
        <NButton size="small" @click="resetFilters">重置</NButton>
      </NFlex>

      <NDataTable
        remote
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :flex-height="!appStore.isMobile"
        :scroll-x="1600"
        :row-key="(row: Api.Creator.Account) => row.id"
        :checked-row-keys="checkedIds"
        size="small"
        class="sm:h-[calc(100%-56px)]"
        @update:checked-row-keys="onCheckedChange"
      />
    </NCard>

    <CreatorAccountDrawer v-model:visible="detailVisible" :loading="detailLoading" :detail="detailData" />

    <CreatorUpsertDialog
      v-model:visible="upsertVisible"
      :initial="editingAccount"
      :custom-fields="customFields"
      @submit="handleUpsertSubmit"
    />

    <CustomFieldDrawer v-model:visible="customFieldVisible" @changed="reloadCustomFields" />
  </div>
</template>

<style scoped></style>
