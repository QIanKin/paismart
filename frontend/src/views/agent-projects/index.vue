<script setup lang="tsx">
import { computed, h, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  NButton,
  NCard,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NPopconfirm,
  NSelect,
  NSpin,
  NTag
} from 'naive-ui';
import {
  fetchProjectArchive,
  fetchProjectCreate,
  fetchProjectCreateFromTemplate,
  fetchProjectList,
  fetchProjectTemplates
} from '@/service/api';

defineOptions({ name: 'AgentProjects' });

const router = useRouter();

const loading = ref(false);
const rows = ref<Api.Project.Item[]>([]);
const templates = ref<Api.Project.Template[]>([]);

async function load() {
  loading.value = true;
  try {
    const [{ data: list }, { data: tpls }] = await Promise.all([fetchProjectList(), fetchProjectTemplates()]);
    rows.value = list ?? [];
    templates.value = tpls ?? [];
  } finally {
    loading.value = false;
  }
}

// 活跃项目优先 + 最近更新靠前
const visibleProjects = computed(() => {
  const items = [...rows.value];
  items.sort((a, b) => {
    const sa = a.status === 'ARCHIVED' ? 1 : 0;
    const sb = b.status === 'ARCHIVED' ? 1 : 0;
    if (sa !== sb) return sa - sb;
    const ta = a.updatedAt ? new Date(a.updatedAt).getTime() : 0;
    const tb = b.updatedAt ? new Date(b.updatedAt).getTime() : 0;
    return tb - ta;
  });
  return items;
});

// 创建
const editVisible = ref(false);
const editing = ref<{
  name: string;
  description: string;
  systemPrompt: string;
  templateCode?: string | null;
}>({
  name: '',
  description: '',
  systemPrompt: '',
  templateCode: null
});

const templateOptions = computed(() => [
  { label: '不使用模板（从空白开始）', value: null as any },
  ...templates.value.map(t => ({ label: `${t.name}（${t.code}）`, value: t.code }))
]);

function openCreate() {
  editing.value = { name: '', description: '', systemPrompt: '', templateCode: null };
  editVisible.value = true;
}

async function handleSubmit() {
  if (!editing.value.name) {
    window.$message?.warning('请输入项目名称');
    return;
  }
  if (editing.value.templateCode) {
    const { error, data } = await fetchProjectCreateFromTemplate(editing.value.templateCode, editing.value.name);
    if (!error) {
      window.$message?.success('已基于模板创建');
      editVisible.value = false;
      await load();
      if (data?.id) router.push({ name: 'agent-project-detail', params: { id: String(data.id) } });
    }
    return;
  }
  const { error, data } = await fetchProjectCreate({
    name: editing.value.name,
    description: editing.value.description,
    systemPrompt: editing.value.systemPrompt
  });
  if (!error) {
    window.$message?.success('已创建');
    editVisible.value = false;
    await load();
    if (data?.id) router.push({ name: 'agent-project-detail', params: { id: String(data.id) } });
  }
}

async function handleArchive(row: Api.Project.Item) {
  const { error } = await fetchProjectArchive(row.id);
  if (!error) {
    window.$message?.success('已归档');
    load();
  }
}

function openProject(row: Api.Project.Item) {
  router.push({ name: 'agent-project-detail', params: { id: String(row.id) } });
}

function parseList(raw?: string | null): string[] {
  if (!raw) return [];
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.map(String) : [];
  } catch {
    return [];
  }
}

onMounted(load);
</script>

<template>
  <div class="flex-col gap-16px">
    <NCard :bordered="false" size="small" class="card-wrapper">
      <div class="flex items-center justify-between">
        <div>
          <div class="text-18px font-semibold">项目工作台</div>
          <div class="mt-4px text-13px text-stone-500">
            一个项目是一次合作 / 一个客户 / 一个活动；点进去开聊，agent 会按项目的上下文、启用的工具 & 技能回答。
          </div>
        </div>
        <NFlex :size="8">
          <NButton size="small" @click="load">刷新</NButton>
          <NButton size="small" type="primary" @click="openCreate">+ 新建项目</NButton>
        </NFlex>
      </div>
    </NCard>

    <NSpin :show="loading">
      <NEmpty
        v-if="!loading && visibleProjects.length === 0"
        description="还没有项目，点右上角「新建项目」开始吧"
        class="py-20"
      />

      <div v-else class="grid gap-16px" style="grid-template-columns: repeat(auto-fill, minmax(300px, 1fr))">
        <NCard
          v-for="row in visibleProjects"
          :key="row.id"
          hoverable
          size="small"
          class="cursor-pointer transition-all hover:shadow-md"
          :class="row.status === 'ARCHIVED' ? 'opacity-60' : ''"
          @click="openProject(row)"
        >
          <div class="flex-col gap-8px">
            <div class="flex items-start justify-between gap-2">
              <div class="flex-col gap-2px">
                <span class="text-15px font-semibold leading-tight">{{ row.name }}</span>
                <span class="text-xs text-stone-500">
                  {{ row.description || '—' }}
                </span>
              </div>
              <NTag size="small" :type="row.status === 'ARCHIVED' ? 'default' : 'success'" :bordered="false">
                {{ row.status === 'ARCHIVED' ? '已归档' : '进行中' }}
              </NTag>
            </div>

            <div v-if="parseList(row.enabledTools).length" class="flex flex-wrap gap-1">
              <NTag v-for="t in parseList(row.enabledTools).slice(0, 4)" :key="t" size="tiny" :bordered="false">
                {{ t }}
              </NTag>
              <span v-if="parseList(row.enabledTools).length > 4" class="text-xs text-stone-400">
                +{{ parseList(row.enabledTools).length - 4 }}
              </span>
            </div>

            <div v-if="parseList(row.enabledSkills).length" class="flex flex-wrap gap-1">
              <NTag
                v-for="s in parseList(row.enabledSkills).slice(0, 3)"
                :key="s"
                size="tiny"
                type="warning"
                :bordered="false"
              >
                skill · {{ s }}
              </NTag>
            </div>

            <div class="mt-4px flex items-center justify-between text-xs text-stone-400">
              <span>
                {{
                  row.updatedAt
                    ? new Date(row.updatedAt).toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
                    : '从未'
                }}
              </span>
              <NPopconfirm @positive-click.stop="handleArchive(row)" @click.stop>
                <template #trigger>
                  <NButton text size="tiny" type="error" :disabled="row.status === 'ARCHIVED'" @click.stop>
                    归档
                  </NButton>
                </template>
                确认归档项目「{{ row.name }}」？
              </NPopconfirm>
            </div>
          </div>
        </NCard>
      </div>
    </NSpin>

    <NModal v-model:show="editVisible" title="新建项目" preset="card" :style="{ width: '560px' }">
      <NForm label-placement="top">
        <NFormItem label="项目名称" required>
          <NInput v-model:value="editing.name" placeholder="如：美妆 · 618 博主批量谈单" />
        </NFormItem>
        <NFormItem label="项目描述">
          <NInput
            v-model:value="editing.description"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="这个项目主要做什么？"
          />
        </NFormItem>
        <NFormItem label="基于模板创建（可选）">
          <NSelect v-model:value="editing.templateCode" :options="templateOptions" clearable />
        </NFormItem>
        <NFormItem v-if="!editing.templateCode" label="系统 Prompt（可选）">
          <NInput
            v-model:value="editing.systemPrompt"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 8 }"
            placeholder="给这个项目下所有会话的默认指令；比如「你是 MCN 谈单专家，每次输出要给明确报价和 next step」"
          />
        </NFormItem>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-2">
          <NButton @click="editVisible = false">取消</NButton>
          <NButton type="primary" @click="handleSubmit">创建并进入</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>
