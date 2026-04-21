<script setup lang="tsx">
/**
 * 小红书 · 竞品笔记监控（xhsCompetitorNote_website 接入）面板。
 *
 * xhsCompetitorNote_website 本身是独立的 Next.js + Supabase 产品，PaiSmart 只消费它的数据。
 * 本面板把"连接 Supabase 所需的凭证"存到后端 platform='xhs_competitor' 的 XhsCookie 记录里：
 *
 *   {"supabaseUrl":"https://xxx.supabase.co","supabaseAnonKey":"...","monitorApi":"https://..."}
 *
 * agent 侧会通过 skills 用这套凭证去 Supabase 拿竞品笔记表。
 */
import { computed, reactive, ref } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDivider,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NScrollbar,
  NSpace,
  NTag
} from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchXhsCookieCreate, fetchXhsCookieDelete, fetchXhsCookieUpdate } from '@/service/api';

interface Props {
  items: Api.Xhs.Cookie[];
}
defineProps<Props>();
const emit = defineEmits<{ (e: 'changed'): void }>();

const visible = ref(false);
const submitting = ref(false);
const editingId = ref<number | null>(null);

const form = reactive({
  supabaseUrl: '',
  supabaseAnonKey: '',
  monitorApi: '',
  accountLabel: '',
  priority: 10,
  note: ''
});

function resetForm() {
  form.supabaseUrl = '';
  form.supabaseAnonKey = '';
  form.monitorApi = '';
  form.accountLabel = '';
  form.priority = 10;
  form.note = '';
}

function openCreate() {
  editingId.value = null;
  resetForm();
  visible.value = true;
}

function openEdit(row: Api.Xhs.Cookie) {
  editingId.value = row.id;
  form.accountLabel = row.accountLabel ?? '';
  form.priority = row.priority ?? 10;
  form.note = row.note ?? '';
  form.supabaseUrl = '';
  form.supabaseAnonKey = '';
  form.monitorApi = '';
  visible.value = true;
}

async function handleSubmit() {
  const hasNewCred = form.supabaseUrl.trim() || form.supabaseAnonKey.trim() || form.monitorApi.trim();
  if (!editingId.value && !hasNewCred) {
    window.$message?.warning('新增时 supabaseUrl / supabaseAnonKey 至少要填一个');
    return;
  }
  submitting.value = true;
  try {
    if (editingId.value != null) {
      const payload: Api.Xhs.CookieUpdatePayload = {
        accountLabel: form.accountLabel.trim() || null,
        priority: form.priority ?? null,
        note: form.note.trim() || null
      };
      if (hasNewCred) {
        payload.cookie = JSON.stringify({
          supabaseUrl: form.supabaseUrl.trim(),
          supabaseAnonKey: form.supabaseAnonKey.trim(),
          monitorApi: form.monitorApi.trim()
        });
      }
      const { error } = await fetchXhsCookieUpdate(editingId.value, payload);
      if (!error) {
        window.$message?.success('已保存');
        visible.value = false;
        emit('changed');
      }
    } else {
      const cookie = JSON.stringify({
        supabaseUrl: form.supabaseUrl.trim(),
        supabaseAnonKey: form.supabaseAnonKey.trim(),
        monitorApi: form.monitorApi.trim()
      });
      const { error } = await fetchXhsCookieCreate({
        platform: 'xhs_competitor',
        cookie,
        accountLabel: form.accountLabel.trim() || null,
        note: form.note.trim() || null,
        priority: form.priority ?? null
      });
      if (!error) {
        window.$message?.success('已添加');
        visible.value = false;
        emit('changed');
      }
    }
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(id: number) {
  const { error } = await fetchXhsCookieDelete(id);
  if (!error) {
    window.$message?.success('已删除');
    emit('changed');
  }
}

const statusType: Record<Api.Xhs.Status, 'success' | 'error' | 'warning' | 'default'> = {
  ACTIVE: 'success',
  EXPIRED: 'warning',
  BANNED: 'error',
  DISABLED: 'default'
};

const columns = computed<DataTableColumns<Api.Xhs.Cookie>>(() => [
  {
    key: 'accountLabel',
    title: '监控实例',
    minWidth: 200,
    render: row => (
      <div class="flex items-center gap-2">
        <SvgIcon icon="solar:eye-scan-bold-duotone" class="text-18px text-#37c2ff" />
        <div class="flex-col">
          <span class="font-semibold">{row.accountLabel || '未命名'}</span>
          <span class="text-xs text-stone-400">{row.note || '—'}</span>
        </div>
      </div>
    )
  },
  {
    key: 'priority',
    title: '优先级',
    width: 90,
    render: row => row.priority
  },
  {
    key: 'status',
    title: '状态',
    width: 100,
    render: row => <NTag type={statusType[row.status] || 'default'}>{row.status}</NTag>
  },
  {
    key: 'health',
    title: '成功/失败',
    width: 110,
    render: row => `${row.successCount ?? 0} / ${row.failCount ?? 0}`
  },
  {
    key: 'lastUsedAt',
    title: '最后使用',
    width: 150,
    render: row => (row.lastUsedAt ? row.lastUsedAt.slice(5, 16).replace('T', ' ') : '—')
  },
  {
    key: 'operate',
    title: '操作',
    width: 180,
    fixed: 'right',
    render: row => (
      <NSpace size={6}>
        <NButton size="small" ghost type="primary" onClick={() => openEdit(row)}>
          编辑
        </NButton>
        <NPopconfirm onPositiveClick={() => handleDelete(row.id)}>
          {{
            default: () => '删除后 agent 将不能查竞品笔记',
            trigger: () => (
              <NButton size="small" ghost type="error">
                删除
              </NButton>
            )
          }}
        </NPopconfirm>
      </NSpace>
    )
  }
]);
</script>

<template>
  <div class="h-full flex-col">
    <NScrollbar style="height: 100%">
      <div class="flex-col gap-12px pb-12px">
        <NAlert type="info" :show-icon="true" title="关于竞品笔记监控">
          <div class="text-12px leading-relaxed">
            <b>xhsCompetitorNote_website</b> 是一套独立部署的 Next.js + Supabase 竞品监控工具：它会定时抓取你关注的
            <b>小红书竞品博主的新笔记</b>，并在 Supabase 里维护一张 notes 表。PaiSmart
            不会替你部署它，只消费它的数据。配置好 Supabase 连接后，agent 就能用 skill
            <code>xhs_competitor.search_notes</code> 查询竞品最新动态。
          </div>
        </NAlert>

        <div class="flex flex-wrap items-center justify-between gap-2">
          <span class="text-13px text-stone-500">共 {{ items.length }} 条竞品监控实例</span>
          <NButton size="small" type="primary" @click="openCreate">+ 新增监控实例</NButton>
        </div>

        <NDataTable
          :columns="columns"
          :data="items"
          size="small"
          :scroll-x="900"
          :row-key="row => row.id"
        />

        <NDivider>接入步骤</NDivider>
        <div class="grid grid-cols-1 gap-12px md:grid-cols-3">
          <NCard size="small" :bordered="false" content-style="padding: 14px 16px">
            <div class="text-13px font-semibold pb-6px">① 部署 Next.js 站点</div>
            <div class="text-12px leading-relaxed text-stone-600 dark:text-stone-300">
              `xhsCompetitorNote_website` 自带 Supabase 迁移脚本，照 README 部署即可。
            </div>
          </NCard>
          <NCard size="small" :bordered="false" content-style="padding: 14px 16px">
            <div class="text-13px font-semibold pb-6px">② 取 Supabase 凭证</div>
            <div class="text-12px leading-relaxed text-stone-600 dark:text-stone-300">
              在 Supabase → Project Settings → API，拷贝 <code>URL</code> 和
              <code>anon public key</code>。
            </div>
          </NCard>
          <NCard size="small" :bordered="false" content-style="padding: 14px 16px">
            <div class="text-13px font-semibold pb-6px">③ 填到本页</div>
            <div class="text-12px leading-relaxed text-stone-600 dark:text-stone-300">
              点「+ 新增监控实例」把 URL 和 key 填上，可选填 <code>monitorApi</code>（你自己站点的 REST 入口）。
            </div>
          </NCard>
        </div>
      </div>
    </NScrollbar>

    <NModal
      v-model:show="visible"
      preset="dialog"
      :title="editingId ? '编辑竞品监控凭证' : '新增竞品监控凭证'"
      :show-icon="false"
      :mask-closable="false"
      class="w-580px!"
    >
      <NForm :model="form" label-placement="left" :label-width="130" mt-10>
        <NFormItem label="Supabase URL">
          <NInput v-model:value="form.supabaseUrl" placeholder="https://xxxx.supabase.co" />
        </NFormItem>
        <NFormItem label="anon public key">
          <NInput
            v-model:value="form.supabaseAnonKey"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            :placeholder="editingId ? '留空 = 不覆盖' : 'eyJhbGciOi...'"
          />
        </NFormItem>
        <NFormItem label="监控站 API">
          <NInput v-model:value="form.monitorApi" placeholder="可选：https://your-next-app.vercel.app/api" />
        </NFormItem>
        <NFormItem label="实例备注">
          <NInput v-model:value="form.accountLabel" placeholder="便于区分，例如：本品+3个主要竞品" maxlength="64" />
        </NFormItem>
        <NFormItem label="优先级">
          <NInputNumber v-model:value="form.priority" class="w-full" :min="0" :max="100" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="form.note" maxlength="128" />
        </NFormItem>
      </NForm>
      <template #action>
        <NSpace :size="12">
          <NButton @click="visible = false">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="handleSubmit">
            {{ editingId ? '保存' : '添加' }}
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>
