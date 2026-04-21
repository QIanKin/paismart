<script setup lang="ts">
/**
 * 项目详情页 · 两栏布局。
 *
 * 左侧：会话侧栏（每个会话就是一段独立的 AI 记忆）
 * 中部：Agent 聊天（保留原 agent-chat 组件不动）
 * 右侧：项目概览 + 博主名册，以抽屉形式从右侧滑出，避免跟聊天争抢视线。
 *
 * 设计目标：
 *  - 一屏之内能直接看到 "项目上下文 · 当前会话 · 对话正文"
 *  - 名册 / 概览不再作为 Tab，改成可随时唤起的右侧抽屉
 *  - Header 更克制：只留关键元数据 + 一组主操作
 */
import { computed, nextTick, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  NBadge,
  NButton,
  NCard,
  NDivider,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NScrollbar,
  NSpin,
  NTag,
  NTooltip
} from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchProjectDetail } from '@/service/api';
import SessionSidebar from './modules/session-sidebar.vue';
import AgentChat from './modules/agent-chat.vue';
import RosterPanel from './modules/roster-panel.vue';

defineOptions({ name: 'AgentProjectDetail' });

const route = useRoute();
const router = useRouter();

const projectId = computed(() => {
  const raw = route.params.id;
  const v = Number(Array.isArray(raw) ? raw[0] : raw);
  return Number.isFinite(v) && v > 0 ? v : 0;
});

const project = ref<Api.Project.Item | null>(null);
const loading = ref(false);
const activeSessionId = ref<number | null>(null);
const sidebarRef = ref<InstanceType<typeof SessionSidebar> | null>(null);

const rosterOpen = ref(false);
const briefOpen = ref(false);

async function loadProject() {
  if (!projectId.value) return;
  loading.value = true;
  try {
    const { data } = await fetchProjectDetail(projectId.value);
    project.value = data || null;
  } finally {
    loading.value = false;
  }
}

watch(projectId, loadProject, { immediate: true });

function parseList(raw?: string | null | string[]): string[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw.map(String);
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.map(String) : [];
  } catch {
    return [];
  }
}

/** 后端 Project 实体会把字段序列化成 enabledToolsJson / enabledSkillsJson，兼容两种形状。 */
function projectField(name: 'enabledTools' | 'enabledSkills'): string | string[] | null | undefined {
  const p = project.value as (Api.Project.Item & { enabledToolsJson?: string; enabledSkillsJson?: string }) | null;
  if (!p) return null;
  return (p as any)[name] ?? (p as any)[`${name}Json`] ?? null;
}

const tools = computed(() => parseList(projectField('enabledTools')));
const skills = computed(() => parseList(projectField('enabledSkills')));

function onSessionChange(sid: number) {
  activeSessionId.value = sid || null;
}

function backToList() {
  router.push({ name: 'agent-projects' });
}

/** 名册抽屉点"方案/审稿/数据" → 侧栏按需创建/选中对应会话。 */
async function openSessionFromRoster(creatorId: number, type: Api.Session.SessionType) {
  rosterOpen.value = false;
  await nextTick();
  await sidebarRef.value?.openSessionFor?.(creatorId, type);
}
</script>

<template>
  <div class="h-full flex-col gap-12px overflow-hidden">
    <!-- ========== Header ========== -->
    <NCard :bordered="false" size="small" class="shrink-0 card-wrapper" content-style="padding: 14px 20px">
      <NSpin :show="loading" :delay="120">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <!-- 左：标题 + 描述 -->
          <div class="min-w-0 flex-1 flex-col gap-4px">
            <div class="flex items-center gap-2">
              <NButton text size="small" @click="backToList">
                <template #icon>
                  <SvgIcon icon="solar:arrow-left-linear" />
                </template>
                项目列表
              </NButton>
              <span class="text-#d1d5db">/</span>
              <span class="truncate text-17px font-semibold">{{ project?.name || '加载中…' }}</span>
              <NTag
                v-if="project"
                size="tiny"
                :type="project.status === 'ARCHIVED' ? 'default' : 'success'"
                :bordered="false"
              >
                {{ project.status === 'ARCHIVED' ? '已归档' : '进行中' }}
              </NTag>
            </div>
            <div class="truncate text-13px text-stone-500">
              {{ project?.description || '暂无项目描述' }}
            </div>
          </div>

          <!-- 右：操作 + 能力标签 -->
          <div class="flex items-center gap-2">
            <NTooltip>
              <template #trigger>
                <NButton size="small" tertiary @click="briefOpen = true">
                  <template #icon>
                    <SvgIcon icon="solar:document-text-bold-duotone" />
                  </template>
                  项目简报
                </NButton>
              </template>
              查看此项目的 system prompt 与启用能力
            </NTooltip>
            <NBadge :show="false">
              <NButton size="small" type="primary" ghost @click="rosterOpen = true">
                <template #icon>
                  <SvgIcon icon="solar:users-group-rounded-bold-duotone" />
                </template>
                博主名册
              </NButton>
            </NBadge>
          </div>
        </div>

        <!-- 能力小条：tools / skills 一眼就能看到 -->
        <div v-if="tools.length || skills.length" class="mt-10px flex flex-wrap items-center gap-1 text-12px">
          <SvgIcon icon="solar:magic-stick-3-bold-duotone" class="text-14px text-stone-400" />
          <span class="text-stone-400">已启用：</span>
          <NTag v-for="t in tools.slice(0, 6)" :key="`t-${t}`" size="tiny" :bordered="false" type="info">
            tool · {{ t }}
          </NTag>
          <NTag v-if="tools.length > 6" size="tiny" :bordered="false">+{{ tools.length - 6 }} tools</NTag>
          <NTag v-for="s in skills.slice(0, 4)" :key="`s-${s}`" size="tiny" type="warning" :bordered="false">
            skill · {{ s }}
          </NTag>
          <NTag v-if="skills.length > 4" size="tiny" :bordered="false">+{{ skills.length - 4 }} skills</NTag>
        </div>
      </NSpin>
    </NCard>

    <!-- ========== Body：两栏 ========== -->
    <NCard
      v-if="projectId"
      :bordered="false"
      size="small"
      class="h-0 flex-auto overflow-hidden card-wrapper"
      content-style="height: 100%; padding: 0;"
    >
      <div class="h-full flex overflow-hidden">
        <!-- 左：会话列表（ = 该项目下的 AI 记忆列表） -->
        <div class="h-full shrink-0" style="width: 280px">
          <SessionSidebar
            ref="sidebarRef"
            :project-id="projectId"
            :active-id="activeSessionId"
            @change="onSessionChange"
          />
        </div>
        <!-- 右：会话主区 -->
        <div class="h-full flex-auto">
          <AgentChat v-if="activeSessionId" :project-id="projectId" :session-id="activeSessionId" />
          <div v-else class="h-full flex-center flex-col gap-3 px-8">
            <NEmpty description="每个会话就是一段独立的 AI 记忆">
              <template #extra>
                <div class="text-xs text-stone-400">
                  在左侧选一条已有会话继续聊，或点「+ 新建会话」开一段新记忆。
                  <br />分配 / 方案 / 审稿 / 数据四种会话类型会自动带上对应工具上下文。
                </div>
              </template>
            </NEmpty>
          </div>
        </div>
      </div>
    </NCard>
    <NEmpty v-else description="项目不存在或无权访问" class="py-20" />

    <!-- ========== 项目简报抽屉（system prompt + 能力清单） ========== -->
    <NDrawer v-model:show="briefOpen" :width="460" placement="right">
      <NDrawerContent title="项目简报" closable>
        <div class="flex-col gap-16px">
          <div>
            <div class="pb-6px text-13px font-semibold">基本信息</div>
            <div class="flex-col gap-4px text-13px">
              <div class="flex gap-2">
                <span class="w-80px shrink-0 text-stone-500">名称</span>
                <span>{{ project?.name || '—' }}</span>
              </div>
              <div class="flex gap-2">
                <span class="w-80px shrink-0 text-stone-500">描述</span>
                <span class="whitespace-pre-wrap">{{ project?.description || '—' }}</span>
              </div>
              <div class="flex gap-2">
                <span class="w-80px shrink-0 text-stone-500">组织</span>
                <span>{{ project?.orgTag || '—' }}</span>
              </div>
              <div class="flex gap-2">
                <span class="w-80px shrink-0 text-stone-500">状态</span>
                <NTag size="tiny" :bordered="false" :type="project?.status === 'ARCHIVED' ? 'default' : 'success'">
                  {{ project?.status || 'ACTIVE' }}
                </NTag>
              </div>
            </div>
          </div>

          <NDivider />

          <div>
            <div class="pb-6px text-13px font-semibold">System Prompt</div>
            <NScrollbar style="max-height: 240px">
              <div class="whitespace-pre-wrap rounded-6px bg-stone-100/40 p-12px text-13px leading-[1.7] dark:bg-stone-800/40">
                {{ project?.systemPrompt || '（此项目未设置专用 system prompt，会使用租户默认）' }}
              </div>
            </NScrollbar>
          </div>

          <NDivider />

          <div>
            <div class="pb-6px text-13px font-semibold">启用的工具 ({{ tools.length }})</div>
            <div v-if="tools.length" class="flex flex-wrap gap-1">
              <NTag v-for="t in tools" :key="`brief-t-${t}`" size="small" :bordered="false" type="info">
                {{ t }}
              </NTag>
            </div>
            <span v-else class="text-xs text-stone-400">未限定工具，使用平台全部可用 tool</span>
          </div>

          <div>
            <div class="pb-6px text-13px font-semibold">启用的技能 ({{ skills.length }})</div>
            <div v-if="skills.length" class="flex flex-wrap gap-1">
              <NTag v-for="s in skills" :key="`brief-s-${s}`" size="small" :bordered="false" type="warning">
                {{ s }}
              </NTag>
            </div>
            <span v-else class="text-xs text-stone-400">未限定技能</span>
          </div>
        </div>
      </NDrawerContent>
    </NDrawer>

    <!-- ========== 博主名册抽屉 ========== -->
    <NDrawer v-model:show="rosterOpen" :width="960" placement="right">
      <NDrawerContent title="博主名册" closable>
        <RosterPanel :project-id="projectId" @open-session="openSessionFromRoster" />
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped></style>
