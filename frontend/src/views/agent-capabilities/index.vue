<script setup lang="ts">
/**
 * Agent 能力中心
 *
 * 把 PaiSmart Agent「能用的工具」与「会的技能」统一展示给业务用户：
 *  - 「工具」Tab：从 GET /agent/tools/catalog 取已注册的内置工具，按业务域分组。
 *    用户看：每个工具能干啥、是只读还是会写库 / 有没有破坏性副作用。
 *  - 「技能」Tab：从 GET /agent/skills 取 SKILL.md 加载到的所有 skill；可一键
 *    启用/禁用，可查看原文 markdown，对 LOCAL/INSTALLED 来源还能直接编辑写回磁盘并热重载。
 *
 * 设计动机：之前用户只能在聊天里"试"才知道 Agent 能不能做某件事；现在把能力清单做成
 * 一级页面，方便管理员配置 + 业务同学搜索"AI 能不能批量打折报价" 这类问题。
 */
import {
  NAlert,
  NButton,
  NCard,
  NCollapse,
  NCollapseItem,
  NDataTable,
  NEmpty,
  NIcon,
  NInput,
  NModal,
  NSpace,
  NSpin,
  NSwitch,
  NTabPane,
  NTabs,
  NTag,
  NTooltip
} from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { computed, h, onMounted, ref } from 'vue';
import {
  type FeatureFlagView,
  type SkillDetail,
  type SkillManifest,
  type ToolCatalog,
  fetchFeatureFlagSet,
  fetchFeatureFlagsList,
  fetchSkillDetail,
  fetchSkillEditSource,
  fetchSkillSetEnabled,
  fetchSkillsList,
  fetchSkillsReload,
  fetchToolsCatalog
} from '@/service/api';

const activeTab = ref<'tools' | 'skills' | 'sources'>('tools');

// ---------------- Tools ----------------
const toolsLoading = ref(false);
const toolsCatalog = ref<ToolCatalog | null>(null);
const toolsError = ref('');
const toolKeyword = ref('');
const expandedGroupNames = ref<string[]>([]);

const filteredGroups = computed(() => {
  const groups = toolsCatalog.value?.groups || [];
  const k = toolKeyword.value.trim().toLowerCase();
  if (!k) return groups;
  return groups
    .map(g => ({
      ...g,
      tools: g.tools.filter(
        t =>
          t.name.toLowerCase().includes(k)
          || (t.userFacingName && t.userFacingName.toLowerCase().includes(k))
          || (t.description && t.description.toLowerCase().includes(k))
      )
    }))
    .filter(g => g.tools.length > 0);
});

async function loadToolsCatalog() {
  toolsLoading.value = true;
  toolsError.value = '';
  try {
    const { data, error } = await fetchToolsCatalog();
    if (error) {
      toolsError.value = String((error as any)?.message || '加载失败');
      return;
    }
    toolsCatalog.value = data || null;
    // 默认全部展开，让用户一眼看到全貌
    expandedGroupNames.value = (data?.groups || []).map(g => g.id);
  } finally {
    toolsLoading.value = false;
  }
}

// ---------------- Skills ----------------
const skillsLoading = ref(false);
const skills = ref<SkillManifest[]>([]);
const skillKeyword = ref('');
const reloading = ref(false);

const filteredSkills = computed(() => {
  const k = skillKeyword.value.trim().toLowerCase();
  if (!k) return skills.value;
  return skills.value.filter(
    s =>
      s.name.toLowerCase().includes(k) || (s.description || '').toLowerCase().includes(k)
  );
});

async function loadSkills() {
  skillsLoading.value = true;
  try {
    const { data, error } = await fetchSkillsList();
    if (error) {
      window.$message?.error('加载技能失败');
      return;
    }
    skills.value = data || [];
  } finally {
    skillsLoading.value = false;
  }
}

async function handleSkillReload() {
  reloading.value = true;
  try {
    const { data, error } = await fetchSkillsReload();
    if (error) {
      window.$message?.error('热重载失败');
      return;
    }
    window.$message?.success(
      `重载完成：scanned=${data?.scanned ?? 0} added=${data?.added ?? 0} updated=${data?.updated ?? 0} disabled=${data?.disabled ?? 0}`
    );
    await loadSkills();
  } finally {
    reloading.value = false;
  }
}

async function handleToggleEnabled(s: SkillManifest, value: boolean) {
  if (s.id == null) return;
  const { error } = await fetchSkillSetEnabled(s.id, value);
  if (error) {
    window.$message?.error('切换启用状态失败');
    return;
  }
  window.$message?.success(value ? `已启用 ${s.name}` : `已禁用 ${s.name}`);
  await loadSkills();
}

// 详情 / 编辑 modal
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailEditing = ref(false);
const detailSaving = ref(false);
const detailSkill = ref<SkillDetail | null>(null);
const detailDraft = ref('');

const detailEditable = computed(() => {
  if (!detailSkill.value) return false;
  // BUILTIN 来源在后端会被拒绝；前端先灰掉编辑按钮
  return detailSkill.value.source !== 'BUILTIN';
});

async function handleOpenSkillDetail(s: SkillManifest) {
  detailVisible.value = true;
  detailLoading.value = true;
  detailEditing.value = false;
  detailSkill.value = null;
  detailDraft.value = '';
  try {
    const { data, error } = await fetchSkillDetail(s.name);
    if (error) {
      window.$message?.error('加载技能详情失败');
      detailVisible.value = false;
      return;
    }
    detailSkill.value = data || null;
    detailDraft.value = data?.rawMarkdown || data?.instructions || '';
  } finally {
    detailLoading.value = false;
  }
}

async function handleSaveSkillSource() {
  if (!detailSkill.value || detailSkill.value.id == null) return;
  if (!detailDraft.value.trim()) {
    window.$message?.warning('内容不能为空');
    return;
  }
  detailSaving.value = true;
  try {
    const { error } = await fetchSkillEditSource(detailSkill.value.id, detailDraft.value);
    if (error) {
      window.$message?.error('保存失败');
      return;
    }
    window.$message?.success('已保存并热重载');
    detailEditing.value = false;
    await loadSkills();
    await handleOpenSkillDetail({ ...detailSkill.value });
  } finally {
    detailSaving.value = false;
  }
}

const skillColumns = computed<DataTableColumns<SkillManifest>>(() => [
  {
    title: '名称',
    key: 'name',
    width: 220,
    render: row =>
      h(
        NSpace,
        { vertical: true, size: 2 },
        {
          default: () => [
            h('div', { class: 'font-medium' }, row.name),
            row.description ? h('div', { class: 'text-xs text-stone-500 line-clamp-2' }, row.description) : null
          ]
        }
      )
  },
  {
    title: '版本',
    key: 'version',
    width: 90,
    render: row => row.version || '-'
  },
  {
    title: '来源',
    key: 'source',
    width: 110,
    render: row => {
      const src = row.source || 'LOCAL';
      const map: Record<string, { type: any; label: string }> = {
        BUILTIN: { type: 'info', label: '内置' },
        LOCAL: { type: 'success', label: '本地' },
        INSTALLED: { type: 'warning', label: '安装' }
      };
      const m = map[src] || { type: 'default' as const, label: src };
      return h(NTag, { size: 'small', type: m.type, bordered: false }, { default: () => m.label });
    }
  },
  {
    title: '组织',
    key: 'ownerOrgTag',
    width: 110,
    render: row => row.ownerOrgTag || '全局'
  },
  {
    title: '依赖二进制',
    key: 'requiredBins',
    width: 200,
    render: row => {
      const bins = row.requiredBins || [];
      if (bins.length === 0) return h('span', { class: 'text-stone-400' }, '-');
      return h(
        NSpace,
        { size: 4, wrap: true },
        {
          default: () => bins.map(b => h(NTag, { size: 'tiny', bordered: false }, { default: () => b }))
        }
      );
    }
  },
  {
    title: '启用',
    key: 'enabled',
    width: 90,
    render: row =>
      h(NSwitch, {
        value: Boolean(row.enabled),
        loading: false,
        size: 'small',
        disabled: row.id == null,
        onUpdateValue: (val: boolean) => handleToggleEnabled(row, val)
      })
  },
  {
    title: '操作',
    key: 'actions',
    width: 120,
    render: row =>
      h(
        NButton,
        {
          size: 'small',
          quaternary: true,
          type: 'primary',
          onClick: () => handleOpenSkillDetail(row)
        },
        { default: () => '查看 / 编辑' }
      )
  }
]);

// ---------------- Feature Flags（数据源运行时开关） ----------------
const flagsLoading = ref(false);
const flags = ref<FeatureFlagView[]>([]);
const flagBusy = ref<Record<string, boolean>>({});

async function loadFlags() {
  flagsLoading.value = true;
  try {
    const { data, error } = await fetchFeatureFlagsList();
    if (error) {
      window.$message?.error('加载数据源开关失败');
      return;
    }
    flags.value = data || [];
  } finally {
    flagsLoading.value = false;
  }
}

async function handleFlagToggle(f: FeatureFlagView, value: boolean) {
  flagBusy.value[f.key] = true;
  try {
    const { data, error } = await fetchFeatureFlagSet(f.key, value);
    if (error) {
      window.$message?.error('保存失败');
      return;
    }
    flags.value = data || flags.value;
    window.$message?.success(value ? `已开启 ${f.label}` : `已关闭 ${f.label}`);
    // flag 变了之后工具清单也会变（manifest 阶段过滤），刷一下让"启用"列同步
    await loadToolsCatalog();
  } finally {
    delete flagBusy.value[f.key];
  }
}

async function handleFlagClearOverride(f: FeatureFlagView) {
  flagBusy.value[f.key] = true;
  try {
    const { data, error } = await fetchFeatureFlagSet(f.key, null);
    if (error) {
      window.$message?.error('清除失败');
      return;
    }
    flags.value = data || flags.value;
    window.$message?.success(`已恢复 ${f.label} 默认值`);
    await loadToolsCatalog();
  } finally {
    delete flagBusy.value[f.key];
  }
}

const flagColumns = computed<DataTableColumns<FeatureFlagView>>(() => [
  {
    title: '数据源 / 能力',
    key: 'label',
    width: 240,
    render: row =>
      h(
        NSpace,
        { vertical: true, size: 2 },
        {
          default: () => [
            h('div', { class: 'font-medium' }, row.label),
            h('code', { class: 'rounded-4px bg-#f1f1f1 px-1 py-px text-[11px] dark:bg-#272a30' }, row.key)
          ]
        }
      )
  },
  {
    title: '说明',
    key: 'description',
    render: row => h('div', { class: 'text-13px text-stone-600 dark:text-stone-300' }, row.description || '-')
  },
  {
    title: '影响工具',
    key: 'toolPrefixes',
    width: 220,
    render: row => {
      const prefixes = row.toolPrefixes || [];
      if (!prefixes.length) return h('span', { class: 'text-stone-400' }, '-');
      return h(
        NSpace,
        { size: 4, wrap: true },
        {
          default: () =>
            prefixes.map(p =>
              h(NTag, { size: 'tiny', bordered: false, type: 'info' }, { default: () => `${p}*` })
            )
        }
      );
    }
  },
  {
    title: '默认',
    key: 'ymlDefault',
    width: 90,
    render: row =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.ymlDefault ? 'success' : 'default' },
        { default: () => (row.ymlDefault ? '默认开' : '默认关') }
      )
  },
  {
    title: '当前',
    key: 'enabled',
    width: 110,
    render: row =>
      h(NSwitch, {
        value: row.enabled,
        loading: Boolean(flagBusy.value[row.key]),
        size: 'small',
        onUpdateValue: (val: boolean) => handleFlagToggle(row, val)
      })
  },
  {
    title: '覆盖',
    key: 'overridden',
    width: 130,
    render: row =>
      row.overridden
        ? h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              type: 'warning',
              loading: Boolean(flagBusy.value[row.key]),
              onClick: () => handleFlagClearOverride(row)
            },
            { default: () => '恢复默认' }
          )
        : h('span', { class: 'text-xs text-stone-400' }, '走默认值')
  }
]);

onMounted(() => {
  loadToolsCatalog();
  loadSkills();
  loadFlags();
});
</script>

<template>
  <div class="h-full flex-col bg-#fafafa dark:bg-#15171c">
    <div class="flex items-center justify-between gap-4 px-24px py-16px">
      <div class="flex-col gap-2px">
        <div class="text-18px font-semibold">能力中心</div>
        <div class="text-13px text-stone-500">
          盘点小蜜蜂当前掌握的工具与技能；点开某个 skill 可直接编辑 SKILL.md（BUILTIN 除外）。
        </div>
      </div>
      <div class="flex items-center gap-3">
        <NButton size="small" :loading="reloading" @click="handleSkillReload">
          <template #icon>
            <NIcon><i-solar-refresh-circle-bold-duotone /></NIcon>
          </template>
          技能热重载
        </NButton>
      </div>
    </div>

    <NTabs v-model:value="activeTab" type="line" class="flex-auto px-16px" pane-class="!pt-0" pane-style="height: 100%">
      <NTabPane name="tools" tab="工具">
        <div class="h-full flex-col gap-12px py-12px">
          <NSpace size="small" class="px-8px">
            <NInput v-model:value="toolKeyword" size="small" placeholder="按名字 / 描述搜索工具" clearable style="width: 280px" />
            <span v-if="toolsCatalog" class="self-center text-xs text-stone-400">
              共 {{ toolsCatalog.total }} 个工具，分组 {{ toolsCatalog.groups.length }}
            </span>
          </NSpace>
          <NSpin :show="toolsLoading" class="h-0 flex-auto">
            <div class="flex-col gap-12px overflow-y-auto px-8px pb-32px">
              <NAlert v-if="toolsError" type="error" :show-icon="false" closable @close="toolsError = ''">
                {{ toolsError }}
              </NAlert>
              <NEmpty v-if="!toolsLoading && filteredGroups.length === 0" description="没有匹配工具" />
              <NCollapse v-model:expanded-names="expandedGroupNames" :default-expanded-names="expandedGroupNames">
                <NCollapseItem v-for="g in filteredGroups" :key="g.id" :name="g.id">
                  <template #header>
                    <span class="font-medium">{{ g.name }}</span>
                    <span class="ml-2 text-xs text-stone-400">{{ g.tools.length }} / {{ g.count }}</span>
                  </template>
                  <template #header-extra>
                    <span class="text-xs text-stone-400">{{ g.description }}</span>
                  </template>
                  <div class="grid grid-cols-1 gap-10px md:grid-cols-2 xl:grid-cols-3">
                    <NCard v-for="t in g.tools" :key="t.name" size="small" class="!rounded-8px">
                      <div class="flex items-center gap-2">
                        <NIcon size="16" class="text-primary-500"><i-solar-bolt-bold-duotone /></NIcon>
                        <span class="font-medium">{{ t.userFacingName || t.name }}</span>
                        <NTooltip>
                          <template #trigger>
                            <NTag size="tiny" :type="t.readOnly ? 'success' : 'warning'" :bordered="false" class="ml-auto">
                              {{ t.readOnly ? '只读' : '可写' }}
                            </NTag>
                          </template>
                          {{ t.readOnly ? '不会修改任何数据' : '可能写库或调用外部副作用 API' }}
                        </NTooltip>
                        <NTag v-if="t.destructive" size="tiny" type="error" :bordered="false">破坏性</NTag>
                      </div>
                      <div class="mt-1 text-xs text-stone-500">
                        <code class="rounded-4px bg-#f1f1f1 px-1 py-px text-[11px] dark:bg-#272a30">{{ t.name }}</code>
                      </div>
                      <div class="mt-2 line-clamp-3 text-13px leading-[1.6] text-stone-700 dark:text-stone-200">
                        {{ t.description }}
                      </div>
                    </NCard>
                  </div>
                </NCollapseItem>
              </NCollapse>
            </div>
          </NSpin>
        </div>
      </NTabPane>

      <NTabPane name="sources" tab="数据源开关">
        <div class="h-full flex-col gap-12px py-12px">
          <NAlert type="info" :show-icon="false" class="mx-8px">
            关闭某个数据源后，依赖它的工具会立刻从 LLM 的 manifest 里消失（这一轮还没开始的请求）。<br>
            数据库覆盖优先于 application.yml 默认值；想"回归配置文件"就点该行的「恢复默认」。
          </NAlert>
          <div class="h-0 flex-auto overflow-hidden px-8px">
            <NDataTable
              :columns="flagColumns"
              :data="flags"
              :loading="flagsLoading"
              size="small"
              :row-key="(row: FeatureFlagView) => row.key"
              striped
              flex-height
              class="h-full"
            />
          </div>
        </div>
      </NTabPane>

      <NTabPane name="skills" tab="技能">
        <div class="h-full flex-col gap-12px py-12px">
          <NSpace size="small" class="px-8px">
            <NInput v-model:value="skillKeyword" size="small" placeholder="按名字 / 描述搜索 skill" clearable style="width: 280px" />
            <span class="self-center text-xs text-stone-400">共 {{ skills.length }} 个 skill</span>
          </NSpace>
          <div class="h-0 flex-auto overflow-hidden px-8px">
            <NDataTable
              :columns="skillColumns"
              :data="filteredSkills"
              :loading="skillsLoading"
              :pagination="{ pageSize: 20 }"
              size="small"
              :row-key="(row: SkillManifest) => row.id ?? row.name"
              striped
              flex-height
              class="h-full"
            />
          </div>
        </div>
      </NTabPane>
    </NTabs>

    <NModal
      v-model:show="detailVisible"
      preset="card"
      style="width: 880px; max-width: 95vw"
      :title="detailSkill?.name || '技能详情'"
      :bordered="false"
    >
      <NSpin :show="detailLoading">
        <div v-if="detailSkill" class="flex-col gap-12px">
          <NSpace size="small">
            <NTag size="small" :bordered="false" :type="detailSkill.source === 'BUILTIN' ? 'info' : detailSkill.source === 'INSTALLED' ? 'warning' : 'success'">
              {{ detailSkill.source }}
            </NTag>
            <NTag size="small" :bordered="false" :type="detailSkill.enabled ? 'success' : 'default'">
              {{ detailSkill.enabled ? '已启用' : '已禁用' }}
            </NTag>
            <NTag v-if="detailSkill.version" size="small" :bordered="false">v{{ detailSkill.version }}</NTag>
            <NTag v-if="detailSkill.ownerOrgTag" size="small" :bordered="false">org:{{ detailSkill.ownerOrgTag }}</NTag>
          </NSpace>
          <div v-if="detailSkill.description" class="text-13px text-stone-600 dark:text-stone-300">
            {{ detailSkill.description }}
          </div>
          <div v-if="detailSkill.rootPath" class="text-xs text-stone-400">
            路径：<code>{{ detailSkill.rootPath }}</code>
          </div>
          <div v-if="(detailSkill.requiredBins || []).length" class="text-xs text-stone-500">
            依赖二进制：
            <NTag v-for="b in detailSkill.requiredBins" :key="b" size="tiny" :bordered="false" class="ml-1">{{ b }}</NTag>
          </div>

          <NAlert v-if="!detailEditable" type="info" :show-icon="false">
            BUILTIN 来源的 skill 不允许在线编辑，避免改坏内置能力；如确需修改请去后端代码仓库。
          </NAlert>

          <div class="flex items-center justify-between">
            <span class="text-xs text-stone-500">SKILL.md 原文</span>
            <NSpace v-if="detailEditable" size="small">
              <NButton v-if="!detailEditing" size="small" type="primary" ghost @click="detailEditing = true">编辑</NButton>
              <NButton v-else size="small" @click="detailEditing = false">取消</NButton>
              <NButton v-if="detailEditing" size="small" type="primary" :loading="detailSaving" @click="handleSaveSkillSource">
                保存并热重载
              </NButton>
            </NSpace>
          </div>
          <NInput
            v-model:value="detailDraft"
            type="textarea"
            :readonly="!detailEditing"
            :autosize="{ minRows: 14, maxRows: 28 }"
            class="font-mono"
            placeholder="SKILL.md 文本（含 YAML front-matter）"
          />
        </div>
      </NSpin>
    </NModal>
  </div>
</template>

<style scoped>
.font-mono :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12.5px;
  line-height: 1.6;
}
</style>
