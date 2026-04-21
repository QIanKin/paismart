<script setup lang="tsx">
import { computed, h, onMounted, ref } from 'vue';
import {
  NButton,
  NDataTable,
  NEmpty,
  NFlex,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  NSpin,
  NTag
} from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  fetchCreatorList,
  fetchProjectRoster,
  fetchProjectRosterAddBatch,
  fetchProjectRosterRemove,
  fetchProjectRosterStage,
  fetchProjectRosterUpdate
} from '@/service/api';

interface Props {
  projectId: number;
}
const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'open-session', creatorId: number, sessionType: Api.Session.SessionType): void;
}>();

const STAGE_META: Record<Api.Project.RosterStage, { label: string; color: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  CANDIDATE: { label: '候选', color: 'default' },
  SHORTLISTED: { label: '入围', color: 'info' },
  LOCKED: { label: '锁定', color: 'warning' },
  SIGNED: { label: '已签约', color: 'success' },
  PUBLISHED: { label: '已上线', color: 'success' },
  SETTLED: { label: '已结算', color: 'default' },
  DROPPED: { label: '放弃', color: 'error' }
};

const STAGE_OPTIONS: SelectOption[] = (Object.keys(STAGE_META) as Api.Project.RosterStage[]).map(v => ({
  label: STAGE_META[v].label,
  value: v
}));

const loading = ref(false);
const rows = ref<Api.Project.RosterEntry[]>([]);

async function load() {
  if (!props.projectId) return;
  loading.value = true;
  try {
    const { data } = await fetchProjectRoster(props.projectId);
    rows.value = data ?? [];
  } finally {
    loading.value = false;
  }
}

watch(() => props.projectId, load, { immediate: true });

// ============ 添加博主 ============
const addVisible = ref(false);
const addStage = ref<Api.Project.RosterStage>('SHORTLISTED');
const addKeyword = ref('');
const addCandidates = ref<Api.Creator.Person[]>([]);
const addSelectedIds = ref<number[]>([]);
const addSearching = ref(false);
const addSubmitting = ref(false);

async function searchCandidates() {
  addSearching.value = true;
  try {
    const { data } = await fetchCreatorList({
      keyword: addKeyword.value || null,
      page: 0,
      size: 30,
      sort: 'id:desc'
    });
    addCandidates.value = data?.items ?? [];
  } finally {
    addSearching.value = false;
  }
}

function openAdd() {
  addVisible.value = true;
  addKeyword.value = '';
  addSelectedIds.value = [];
  addCandidates.value = [];
  searchCandidates();
}

async function confirmAdd() {
  if (addSelectedIds.value.length === 0) {
    window.$message?.warning('请选择至少一个博主');
    return;
  }
  addSubmitting.value = true;
  try {
    const { error } = await fetchProjectRosterAddBatch(props.projectId, addSelectedIds.value, addStage.value);
    if (!error) {
      window.$message?.success(`已加入 ${addSelectedIds.value.length} 个博主`);
      addVisible.value = false;
      load();
    }
  } finally {
    addSubmitting.value = false;
  }
}

// ============ stage 切换 ============
async function onStageChange(entry: Api.Project.RosterEntry, stage: Api.Project.RosterStage) {
  const { error } = await fetchProjectRosterStage(props.projectId, entry.id, stage);
  if (!error) {
    entry.stage = stage;
    window.$message?.success('阶段已更新');
  }
}

// ============ 编辑条目 ============
const editVisible = ref(false);
const editTarget = ref<Api.Project.RosterEntry | null>(null);
const editDraft = ref<Api.Project.RosterUpsertPayload>({});

function openEdit(entry: Api.Project.RosterEntry) {
  editTarget.value = entry;
  editDraft.value = {
    stage: entry.stage,
    priority: entry.priority ?? null,
    quotedPrice: entry.quotedPrice ?? null,
    currency: entry.currency ?? 'CNY',
    projectNotes: entry.projectNotes ?? null
  };
  editVisible.value = true;
}

async function saveEdit() {
  if (!editTarget.value) return;
  const { error } = await fetchProjectRosterUpdate(props.projectId, editTarget.value.id, editDraft.value);
  if (!error) {
    editVisible.value = false;
    load();
  }
}

async function remove(entry: Api.Project.RosterEntry) {
  const { error } = await fetchProjectRosterRemove(props.projectId, entry.id);
  if (!error) {
    window.$message?.success('已移除');
    load();
  }
}

function parseTags(raw?: string | null): string[] {
  if (!raw) return [];
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.map(String) : [];
  } catch {
    return String(raw)
      .split(/[,，]/)
      .map(x => x.trim())
      .filter(Boolean);
  }
}

const columns = computed<DataTableColumns<Api.Project.RosterEntry>>(() => [
  {
    key: 'creator',
    title: '博主',
    minWidth: 200,
    render: r => (
      <div class="flex-col">
        <span class="font-semibold">{r.creator?.displayName || `Creator #${r.creatorId}`}</span>
        {r.creator?.realName ? <span class="text-xs text-stone-500">真名 {r.creator.realName}</span> : null}
      </div>
    )
  },
  {
    key: 'persona',
    title: '人设 / 赛道',
    minWidth: 220,
    render: r => {
      const persona = parseTags(r.creator?.personaTags);
      const track = parseTags(r.creator?.trackTags);
      return (
        <div class="flex flex-wrap gap-1">
          {track.slice(0, 3).map(t => (
            <NTag key={`t-${t}`} size="tiny" type="info" bordered={false}>
              {t}
            </NTag>
          ))}
          {persona.slice(0, 3).map(t => (
            <NTag key={`p-${t}`} size="tiny" bordered={false}>
              {t}
            </NTag>
          ))}
          {track.length + persona.length === 0 ? <span class="text-stone-400">-</span> : null}
        </div>
      );
    }
  },
  {
    key: 'stage',
    title: '阶段',
    width: 140,
    render: r => (
      <NSelect
        size="small"
        value={r.stage}
        options={STAGE_OPTIONS}
        onUpdateValue={(v: Api.Project.RosterStage) => onStageChange(r, v)}
      />
    )
  },
  {
    key: 'quotedPrice',
    title: '报价',
    width: 120,
    render: r =>
      r.quotedPrice != null ? (
        <span class="font-mono text-13px">
          {r.currency || 'CNY'} {r.quotedPrice}
        </span>
      ) : (
        <span class="text-stone-400">-</span>
      )
  },
  {
    key: 'priority',
    title: '优先级',
    width: 80,
    render: r => <span class="font-mono">{r.priority ?? '-'}</span>
  },
  {
    key: 'projectNotes',
    title: '项目备注',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: r => <span class="text-xs text-stone-500">{r.projectNotes || '-'}</span>
  },
  {
    key: 'operate',
    title: '操作',
    width: 280,
    fixed: 'right',
    render: r => (
      <div class="flex gap-1">
        <NButton
          size="small"
          quaternary
          type="primary"
          onClick={() => emit('open-session', r.creatorId, 'BLOGGER_BRIEF')}
        >
          方案
        </NButton>
        <NButton size="small" quaternary onClick={() => emit('open-session', r.creatorId, 'CONTENT_REVIEW')}>
          审稿
        </NButton>
        <NButton size="small" quaternary onClick={() => emit('open-session', r.creatorId, 'DATA_TRACK')}>
          数据
        </NButton>
        <NButton size="small" quaternary onClick={() => openEdit(r)}>
          编辑
        </NButton>
        <NPopconfirm onPositiveClick={() => remove(r)}>
          {{
            trigger: () => (
              <NButton size="small" quaternary type="error">
                移除
              </NButton>
            ),
            default: () => `确认将 ${r.creator?.displayName || '#' + r.creatorId} 从名册移除？`
          }}
        </NPopconfirm>
      </div>
    )
  }
]);
</script>

<template>
  <div class="h-full flex-col gap-10px">
    <div class="flex items-center justify-between">
      <div class="text-sm text-stone-500">
        共 <span class="text-stone-800 font-semibold dark:text-stone-200">{{ rows.length }}</span> 个博主
      </div>
      <NFlex :size="8">
        <NButton size="small" @click="load">刷新</NButton>
        <NButton size="small" type="primary" @click="openAdd">+ 添加博主</NButton>
      </NFlex>
    </div>

    <NSpin :show="loading" class="h-0 flex-auto">
      <NEmpty v-if="!loading && rows.length === 0" description="名册为空。点「+ 添加博主」开始构建你的项目名册" />
      <NDataTable
        v-else
        :columns="columns"
        :data="rows"
        :row-key="(r: Api.Project.RosterEntry) => r.id"
        size="small"
        :scroll-x="1280"
        :flex-height="true"
      />
    </NSpin>

    <!-- 添加博主 Modal -->
    <NModal v-model:show="addVisible" preset="card" title="添加博主到名册" :style="{ width: '640px' }">
      <NFlex :size="8" class="pb-12px">
        <NInput
          v-model:value="addKeyword"
          placeholder="搜索昵称 / 真名 / 赛道关键字"
          clearable
          @keydown.enter="searchCandidates"
        />
        <NButton :loading="addSearching" @click="searchCandidates">搜索</NButton>
        <NSelect v-model:value="addStage" :options="STAGE_OPTIONS" class="w-32" />
      </NFlex>

      <div class="max-h-320px overflow-auto">
        <NEmpty v-if="!addSearching && addCandidates.length === 0" description="没有结果" />
        <div
          v-for="c in addCandidates"
          :key="c.id"
          class="flex cursor-pointer items-center gap-2 border-b b-stone-200/60 px-1 py-2 hover:bg-stone-100/40 dark:b-stone-700/30 dark:hover:bg-stone-700/30"
          @click="
            addSelectedIds.includes(c.id)
              ? (addSelectedIds = addSelectedIds.filter(x => x !== c.id))
              : addSelectedIds.push(c.id)
          "
        >
          <input type="checkbox" :checked="addSelectedIds.includes(c.id)" @click.stop />
          <div class="flex-col flex-auto">
            <span class="font-semibold">{{ c.displayName }}</span>
            <span class="text-xs text-stone-500">{{ c.personaTags || c.trackTags || '—' }}</span>
          </div>
          <span class="text-xs text-stone-400">#{{ c.id }}</span>
        </div>
      </div>

      <template #footer>
        <div class="flex items-center justify-between">
          <span class="text-xs text-stone-500">已选 {{ addSelectedIds.length }} 个</span>
          <NFlex :size="8">
            <NButton @click="addVisible = false">取消</NButton>
            <NButton type="primary" :loading="addSubmitting" @click="confirmAdd">加入名册</NButton>
          </NFlex>
        </div>
      </template>
    </NModal>

    <!-- 编辑条目 -->
    <NModal v-model:show="editVisible" preset="card" title="编辑名册条目" :style="{ width: '480px' }">
      <div class="flex-col gap-8px">
        <div>
          <div class="pb-1 text-xs text-stone-500">阶段</div>
          <NSelect v-model:value="editDraft.stage" :options="STAGE_OPTIONS" />
        </div>
        <div>
          <div class="pb-1 text-xs text-stone-500">优先级（数值越小越优先）</div>
          <NInputNumber v-model:value="editDraft.priority" :min="0" class="w-full" />
        </div>
        <div class="flex items-center gap-2">
          <div class="flex-auto">
            <div class="pb-1 text-xs text-stone-500">报价</div>
            <NInputNumber v-model:value="editDraft.quotedPrice" :min="0" class="w-full" />
          </div>
          <div class="w-100px">
            <div class="pb-1 text-xs text-stone-500">货币</div>
            <NInput v-model:value="editDraft.currency" placeholder="CNY" />
          </div>
        </div>
        <div>
          <div class="pb-1 text-xs text-stone-500">项目备注</div>
          <NInput v-model:value="editDraft.projectNotes" type="textarea" :rows="3" placeholder="在本项目下的备注…" />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <NButton @click="editVisible = false">取消</NButton>
          <NButton type="primary" @click="saveEdit">保存</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>
