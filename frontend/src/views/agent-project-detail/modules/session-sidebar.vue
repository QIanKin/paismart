<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  NButton,
  NEmpty,
  NInput,
  NModal,
  NPopconfirm,
  NScrollbar,
  NSelect,
  NSpin,
  NTag
} from 'naive-ui';
import type { SelectOption } from 'naive-ui';
import {
  fetchProjectRoster,
  fetchSessionArchive,
  fetchSessionCreate,
  fetchSessionList,
  fetchSessionRename
} from '@/service/api';

interface Props {
  projectId: number;
  activeId: number | null;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'change', id: number): void;
}>();

const loading = ref(false);
const list = ref<Api.Session.Item[]>([]);

const SESSION_TYPE_META: Record<
  Api.Session.SessionType,
  { label: string; color: 'default' | 'info' | 'success' | 'warning' | 'error'; needCreator: boolean }
> = {
  GENERAL: { label: '通用', color: 'default', needCreator: false },
  ALLOCATION: { label: '分配', color: 'warning', needCreator: false },
  BLOGGER_BRIEF: { label: '方案', color: 'info', needCreator: true },
  CONTENT_REVIEW: { label: '审稿', color: 'success', needCreator: true },
  DATA_TRACK: { label: '数据', color: 'default', needCreator: true }
};

const SESSION_TYPE_OPTIONS: SelectOption[] = (Object.keys(SESSION_TYPE_META) as Api.Session.SessionType[]).map(v => ({
  label: SESSION_TYPE_META[v].label + '（' + v + '）',
  value: v
}));

const groups = computed(() => {
  const g: Record<string, Api.Session.Item[]> = {
    ALLOCATION: [],
    BLOGGER_BRIEF: [],
    CONTENT_REVIEW: [],
    DATA_TRACK: [],
    GENERAL: []
  };
  for (const s of list.value) {
    const t = (s.sessionType as Api.Session.SessionType) || 'GENERAL';
    (g[t] ?? (g.GENERAL = g.GENERAL || [])).push(s);
  }
  return g;
});

async function load(selectFirst = false) {
  if (!props.projectId) return;
  loading.value = true;
  try {
    const { data } = await fetchSessionList(props.projectId);
    list.value = (data ?? []).filter(s => s.status !== 'ARCHIVED');
    list.value.sort((a, b) => (b.lastMessageAt || b.createdAt || '').localeCompare(a.lastMessageAt || a.createdAt || ''));
    if (selectFirst && list.value.length && !props.activeId) {
      emit('change', list.value[0].id);
    }
  } finally {
    loading.value = false;
  }
}

watch(() => props.projectId, () => load(true), { immediate: true });

// ============ 新建会话 ============
const createVisible = ref(false);
const createLoading = ref(false);
const newTitle = ref('');
const newType = ref<Api.Session.SessionType>('GENERAL');
const newCreatorId = ref<number | null>(null);
const rosterOptions = ref<SelectOption[]>([]);

async function openCreate(defaultType: Api.Session.SessionType = 'GENERAL') {
  newTitle.value = '';
  newType.value = defaultType;
  newCreatorId.value = null;
  createVisible.value = true;
  // 预取名册给 creatorId 下拉用
  try {
    const { data } = await fetchProjectRoster(props.projectId);
    rosterOptions.value = (data ?? []).map(r => ({
      label: `${r.creator?.displayName || 'Creator'} · #${r.creatorId}`,
      value: r.creatorId
    }));
  } catch {
    rosterOptions.value = [];
  }
}

/**
 * 外部（比如博主名册 Tab）可以直接 openSessionFor(creatorId, 'BLOGGER_BRIEF')，
 * 这时不弹对话框，直接尝试找已有会话，没有就创建一个。
 */
async function openSessionFor(creatorId: number, type: Api.Session.SessionType) {
  const existing = list.value.find(s => (s.sessionType || 'GENERAL') === type && s.creatorId === creatorId);
  if (existing) {
    emit('change', existing.id);
    return;
  }
  const { data, error } = await fetchSessionCreate({
    projectId: props.projectId,
    sessionType: type,
    creatorId,
    title: null
  });
  if (!error && data?.id) {
    await load();
    emit('change', data.id);
  }
}

defineExpose({ reload: () => load(), openSessionFor });

async function submitCreate() {
  const meta = SESSION_TYPE_META[newType.value];
  if (meta.needCreator && !newCreatorId.value) {
    window.$message?.warning(`「${meta.label}」会话必须绑定一个博主`);
    return;
  }
  createLoading.value = true;
  try {
    const { data, error } = await fetchSessionCreate({
      projectId: props.projectId,
      title: newTitle.value || null,
      sessionType: newType.value,
      creatorId: newCreatorId.value
    });
    if (!error && data?.id) {
      createVisible.value = false;
      await load();
      emit('change', data.id);
    }
  } finally {
    createLoading.value = false;
  }
}

// ============ 重命名 ============
const renameVisible = ref(false);
const renameTarget = ref<Api.Session.Item | null>(null);
const renameDraft = ref('');

function openRename(item: Api.Session.Item) {
  renameTarget.value = item;
  renameDraft.value = item.title || '';
  renameVisible.value = true;
}

async function submitRename() {
  if (!renameTarget.value) return;
  const { error } = await fetchSessionRename(renameTarget.value.id, renameDraft.value || '未命名会话');
  if (!error) {
    renameVisible.value = false;
    load();
  }
}

async function doArchive(item: Api.Session.Item) {
  const { error } = await fetchSessionArchive(item.id);
  if (!error) {
    window.$message?.success('已归档');
    if (props.activeId === item.id) emit('change', 0);
    load();
  }
}

function titleOf(item: Api.Session.Item) {
  if (item.title && item.title.trim()) return item.title.trim();
  const t = (item.sessionType as Api.Session.SessionType) || 'GENERAL';
  return `${SESSION_TYPE_META[t].label} 会话 #${item.id}`;
}

function typeTag(item: Api.Session.Item) {
  const t = (item.sessionType as Api.Session.SessionType) || 'GENERAL';
  return SESSION_TYPE_META[t];
}

const GROUP_ORDER: Array<{ key: Api.Session.SessionType; label: string }> = [
  { key: 'ALLOCATION', label: '博主分配' },
  { key: 'BLOGGER_BRIEF', label: '博主方案' },
  { key: 'CONTENT_REVIEW', label: '内容审稿' },
  { key: 'DATA_TRACK', label: '数据追踪' },
  { key: 'GENERAL', label: '通用对话' }
];
</script>

<template>
  <div class="h-full flex-col gap-8px border-r b-#e5e7eb20 px-12px py-12px dark:b-#1f2937">
    <div class="flex items-center gap-2">
      <NButton size="small" type="primary" block @click="openCreate('GENERAL')">+ 新建会话</NButton>
    </div>
    <div class="flex flex-wrap gap-1">
      <NButton size="tiny" quaternary @click="openCreate('ALLOCATION')">+ 分配</NButton>
      <NButton size="tiny" quaternary @click="openCreate('BLOGGER_BRIEF')">+ 方案</NButton>
      <NButton size="tiny" quaternary @click="openCreate('CONTENT_REVIEW')">+ 审稿</NButton>
      <NButton size="tiny" quaternary @click="openCreate('DATA_TRACK')">+ 数据</NButton>
    </div>

    <NSpin :show="loading" class="h-0 flex-auto">
      <NScrollbar style="max-height: 100%">
        <div v-if="!loading && list.length === 0" class="py-10">
          <NEmpty description="这个项目下还没有会话，新建一个吧" />
        </div>
        <template v-else>
          <div v-for="g in GROUP_ORDER" :key="g.key">
            <div v-if="groups[g.key] && groups[g.key].length" class="mb-1 mt-2 px-1 text-xs text-stone-400">
              {{ g.label }} · {{ groups[g.key].length }}
            </div>
            <div
              v-for="item in groups[g.key] || []"
              :key="item.id"
              class="group flex-col cursor-pointer rounded-6px px-10px py-8px transition-colors"
              :class="activeId === item.id ? 'bg-primary-1 dark:bg-#303a4a' : 'hover:bg-#f3f4f620 dark:hover:bg-#2b2e34'"
              @click="emit('change', item.id)"
            >
              <div class="flex items-center justify-between gap-2">
                <span class="flex-auto truncate text-13px" :class="activeId === item.id ? 'font-semibold' : ''">
                  {{ titleOf(item) }}
                </span>
                <NTag :type="typeTag(item).color" size="tiny" :bordered="false" class="shrink-0">
                  {{ typeTag(item).label }}
                </NTag>
              </div>
              <div class="mt-2px flex items-center justify-between">
                <span class="text-xs text-stone-400">
                  {{
                    item.lastMessageAt
                      ? new Date(item.lastMessageAt).toLocaleString('zh-CN', { hour12: false }).slice(5, 16)
                      : '未开聊'
                  }}
                  <span v-if="item.creatorId"> · 博主#{{ item.creatorId }}</span>
                </span>
                <div class="invisible flex items-center gap-1 group-hover:visible" @click.stop>
                  <NButton text size="tiny" @click.stop="openRename(item)">改名</NButton>
                  <NPopconfirm @positive-click.stop="doArchive(item)">
                    <template #trigger>
                      <NButton text size="tiny" type="error" @click.stop>删</NButton>
                    </template>
                    确认归档该会话？
                  </NPopconfirm>
                </div>
              </div>
            </div>
          </div>
        </template>
      </NScrollbar>
    </NSpin>

    <!-- 新建对话框 -->
    <NModal v-model:show="createVisible" preset="card" title="新建会话" :style="{ width: '440px' }">
      <div class="flex-col gap-8px">
        <div>
          <div class="pb-1 text-xs text-stone-500">会话类型</div>
          <NSelect v-model:value="newType" :options="SESSION_TYPE_OPTIONS" />
        </div>
        <div v-if="SESSION_TYPE_META[newType].needCreator">
          <div class="pb-1 text-xs text-stone-500">绑定博主（本项目名册里）</div>
          <NSelect v-model:value="newCreatorId" :options="rosterOptions" placeholder="从名册里选一个博主" />
        </div>
        <div>
          <div class="pb-1 text-xs text-stone-500">标题（可空，留空用默认）</div>
          <NInput v-model:value="newTitle" placeholder="如：首轮方案 / 第二稿审阅" />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <NButton @click="createVisible = false">取消</NButton>
          <NButton type="primary" :loading="createLoading" @click="submitCreate">创建</NButton>
        </div>
      </template>
    </NModal>

    <!-- 重命名 -->
    <NModal v-model:show="renameVisible" preset="card" title="重命名会话" :style="{ width: '420px' }">
      <NInput v-model:value="renameDraft" placeholder="新名称" />
      <template #footer>
        <div class="flex justify-end gap-2">
          <NButton @click="renameVisible = false">取消</NButton>
          <NButton type="primary" @click="submitRename">保存</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>
