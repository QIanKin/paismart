<script setup lang="tsx">
import { computed, ref, watch } from 'vue';
import {
  NButton,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NPagination,
  NSkeleton,
  NSpin,
  NTabPane,
  NTabs,
  NTag
} from 'naive-ui';
import { fetchAccountPosts } from '@/service/api';

interface Props {
  visible: boolean;
  loading: boolean;
  detail: Api.Creator.AccountDetail | null;
}

const props = defineProps<Props>();
const emit = defineEmits<{ 'update:visible': [boolean] }>();

const show = computed({
  get: () => props.visible,
  set: v => emit('update:visible', v)
});

// ============ 最近笔记 Tab ============
const activeTab = ref<'profile' | 'posts'>('profile');
const postsLoading = ref(false);
const posts = ref<Api.Creator.Post[]>([]);
const postsPage = ref(1);
const postsSize = ref(10);
const postsTotal = ref(0);
const postsStale = ref(false);
const postsMostRecent = ref<string | null>(null);

async function loadPosts(refresh = false) {
  const accountId = props.detail?.account?.id;
  if (!accountId) return;
  postsLoading.value = true;
  try {
    const { data } = await fetchAccountPosts(accountId, {
      page: postsPage.value - 1,
      size: postsSize.value,
      refresh,
      ttlHours: 24
    });
    if (data) {
      posts.value = data.items ?? [];
      postsTotal.value = data.total ?? 0;
      postsStale.value = !!data.stale;
      postsMostRecent.value = data.mostRecentSnapshotAt ?? null;
    }
  } finally {
    postsLoading.value = false;
  }
}

watch(
  () => [activeTab.value, props.detail?.account?.id] as const,
  ([tab, id]) => {
    if (tab === 'posts' && id) {
      postsPage.value = 1;
      loadPosts(false);
    }
  }
);

function fmtNum(v?: number | null) {
  if (v === null || v === undefined) return '-';
  if (v >= 10000) return `${(v / 10000).toFixed(1)}w`;
  return String(v);
}

function fmtPct(v?: number | null) {
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

function parseCustomFields(raw?: string | null): Record<string, unknown> {
  if (!raw) return {};
  try {
    const v = JSON.parse(raw);
    return v && typeof v === 'object' ? (v as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}
</script>

<template>
  <NDrawer v-model:show="show" :width="720" placement="right">
    <NDrawerContent title="博主账号详情" closable>
      <NSkeleton v-if="loading" text :repeat="6" />
      <NEmpty v-else-if="!detail" description="无数据" />
      <NTabs v-else v-model:value="activeTab" type="line" animated>
        <NTabPane name="profile" tab="资料">
      <div class="flex-col gap-16px">
        <div class="flex items-center gap-4">
          <img
            v-if="detail.account.avatarUrl"
            :src="detail.account.avatarUrl"
            class="h-16 w-16 b-1 b-stone-200 rounded-full"
          />
          <div class="flex-col">
            <div class="text-5 font-bold">
              {{ detail.account.displayName || detail.account.handle }}
              <NTag v-if="detail.account.verified" size="small" type="success" class="ml-2">已认证</NTag>
            </div>
            <div class="text-3 text-stone-500">
              {{ detail.account.platform }} · @{{ detail.account.handle || detail.account.platformUserId }}
            </div>
            <div class="mt-1 text-3 text-stone-500">
              {{ detail.account.bio || '—' }}
            </div>
          </div>
        </div>

        <NDescriptions bordered :column="3" size="small" label-placement="top">
          <NDescriptionsItem label="粉丝">{{ fmtNum(detail.account.followers) }}</NDescriptionsItem>
          <NDescriptionsItem label="互动率">{{ fmtPct(detail.account.engagementRate) }}</NDescriptionsItem>
          <NDescriptionsItem label="爆款率">{{ fmtPct(detail.account.hitRatio) }}</NDescriptionsItem>
          <NDescriptionsItem label="内容数">{{ fmtNum(detail.account.posts) }}</NDescriptionsItem>
          <NDescriptionsItem label="平均点赞">{{ fmtNum(detail.account.avgLikes) }}</NDescriptionsItem>
          <NDescriptionsItem label="平均评论">{{ fmtNum(detail.account.avgComments) }}</NDescriptionsItem>
          <NDescriptionsItem label="主赛道">{{ detail.account.categoryMain || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="子赛道">{{ detail.account.categorySub || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="地区">{{ detail.account.region || '-' }}</NDescriptionsItem>
        </NDescriptions>

        <div v-if="parseTags(detail.account.platformTags).length" class="flex-col gap-8px">
          <div class="text-3.5 font-bold">平台标签</div>
          <div class="flex flex-wrap gap-2">
            <NTag v-for="t in parseTags(detail.account.platformTags)" :key="t" size="small" bordered>{{ t }}</NTag>
          </div>
        </div>

        <div v-if="Object.keys(parseCustomFields(detail.account.customFields)).length" class="flex-col gap-8px">
          <div class="text-3.5 font-bold">自定义字段</div>
          <NDescriptions bordered :column="2" size="small" label-placement="top">
            <NDescriptionsItem v-for="(v, k) in parseCustomFields(detail.account.customFields)" :key="k" :label="k">
              <span>{{ typeof v === 'object' ? JSON.stringify(v) : String(v) }}</span>
            </NDescriptionsItem>
          </NDescriptions>
        </div>

        <div v-if="detail.creator" class="flex-col gap-8px">
          <div class="text-3.5 font-bold">关联 Creator（人）</div>
          <NDescriptions bordered :column="3" size="small" label-placement="top">
            <NDescriptionsItem label="姓名">{{ detail.creator.displayName }}</NDescriptionsItem>
            <NDescriptionsItem label="真名">{{ detail.creator.realName || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="性别">{{ detail.creator.gender || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="合作状态">{{ detail.creator.cooperationStatus || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="报价备注">{{ detail.creator.priceNote || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="内部备注">{{ detail.creator.internalNotes || '-' }}</NDescriptionsItem>
          </NDescriptions>
        </div>

      </div>
        </NTabPane>

        <NTabPane name="posts" tab="最近笔记">
          <div class="flex-col gap-8px">
            <div class="flex items-center justify-between">
              <div class="text-xs text-stone-500">
                <NTag v-if="postsStale" size="tiny" type="warning" :bordered="false">数据可能过期</NTag>
                <NTag v-else size="tiny" type="success" :bordered="false">缓存新鲜</NTag>
                <span v-if="postsMostRecent" class="ml-2">最近采样：{{ postsMostRecent.slice(0, 16).replace('T', ' ') }}</span>
              </div>
              <NButton size="small" :loading="postsLoading" type="primary" ghost @click="loadPosts(true)">
                强制刷新
              </NButton>
            </div>

            <NSpin :show="postsLoading">
              <NEmpty v-if="!postsLoading && posts.length === 0" description="还没有笔记，点「强制刷新」试试" />
              <div v-else class="flex-col gap-2">
                <div
                  v-for="p in posts"
                  :key="p.id"
                  class="flex items-start gap-3 b-1 b-stone-200 rounded-2 p-3 dark:b-stone-700"
                >
                  <img v-if="p.cover" :src="p.cover" class="h-16 w-16 rounded-1 object-cover" />
                  <div class="min-w-0 flex-col flex-1 gap-1">
                    <a v-if="p.postUrl" :href="p.postUrl" target="_blank" class="font-semibold hover:underline">
                      {{ p.title || p.platformPostId }}
                    </a>
                    <span v-else class="font-semibold">{{ p.title || p.platformPostId }}</span>
                    <span class="text-xs text-stone-500">
                      {{ p.publishedAt?.slice(0, 10) || '-' }} · 赞 {{ fmtNum(p.likes) }} · 评
                      {{ fmtNum(p.comments) }} · 收 {{ fmtNum(p.collects) }}
                      <NTag v-if="p.isHit" size="tiny" type="warning" class="ml-1">爆款</NTag>
                    </span>
                  </div>
                </div>
              </div>
            </NSpin>

            <div class="flex justify-end pt-2">
              <NPagination
                v-model:page="postsPage"
                :page-size="postsSize"
                :item-count="postsTotal"
                :page-sizes="[10, 20, 50]"
                show-size-picker
                @update:page="loadPosts(false)"
                @update:page-size="(v: number) => { postsSize = v; postsPage = 1; loadPosts(false); }"
              />
            </div>
          </div>
        </NTabPane>
      </NTabs>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped></style>
