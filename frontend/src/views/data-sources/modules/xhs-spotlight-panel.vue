<script setup lang="tsx">
/**
 * 小红书 · 聚光广告 MarketingAPI（OAuth2）面板。
 *
 * 凭证在后端存为 platform='xhs_spotlight' 的 XhsCookie 记录，cookie 字段是一段 JSON：
 *   {"advertiserId":"...","accessToken":"...","refreshToken":"...","expiresAt":"..."}
 *
 * 目前本页面只做"凭证录入/展示"，真正的报表拉取工具（agent-side）会在后续版本接入。
 * 参考 SDK：https://github.com/bububa/spotlight-mapi
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
  advertiserId: '',
  accessToken: '',
  refreshToken: '',
  accountLabel: '',
  priority: 10,
  note: ''
});

function resetForm() {
  form.advertiserId = '';
  form.accessToken = '';
  form.refreshToken = '';
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
  // 已入库的 credential 明文不下发；编辑时留空表示不改
  form.advertiserId = '';
  form.accessToken = '';
  form.refreshToken = '';
  visible.value = true;
}

async function handleSubmit() {
  // 编辑态且没填任何新凭证 → 只改 accountLabel/priority/note
  const hasNewCred = form.advertiserId.trim() || form.accessToken.trim() || form.refreshToken.trim();
  if (!editingId.value && !hasNewCred) {
    window.$message?.warning('新增时 advertiserId / accessToken 至少要填一个');
    return;
  }
  submitting.value = true;
  try {
    if (editingId.value != null) {
      // 仅当本次填了新凭证才覆盖 cookie
      const payload: Api.Xhs.CookieUpdatePayload = {
        accountLabel: form.accountLabel.trim() || null,
        priority: form.priority ?? null,
        note: form.note.trim() || null
      };
      if (hasNewCred) {
        payload.cookie = JSON.stringify({
          advertiserId: form.advertiserId.trim(),
          accessToken: form.accessToken.trim(),
          refreshToken: form.refreshToken.trim()
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
        advertiserId: form.advertiserId.trim(),
        accessToken: form.accessToken.trim(),
        refreshToken: form.refreshToken.trim()
      });
      const { error } = await fetchXhsCookieCreate({
        platform: 'xhs_spotlight',
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
    title: '广告主',
    minWidth: 180,
    render: row => (
      <div class="flex items-center gap-2">
        <SvgIcon icon="solar:megaphone-bold-duotone" class="text-18px text-#9575ff" />
        <div class="flex-col">
          <span class="font-semibold">{row.accountLabel || '未命名'}</span>
          <span class="text-xs text-stone-400">{row.cookieKeys || '—'}</span>
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
            default: () => '删除后 agent 将无法访问聚光数据',
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
        <NAlert type="info" :show-icon="true" title="关于聚光 MarketingAPI">
          <div class="text-12px leading-relaxed">
            小红书聚光 MarketingAPI 用于拉取 <b>广告账户层</b> 的真实投放数据（计划/单元/创意/关键词报表），
            <b>需要先在聚光后台申请 OAuth2 授权</b>，拿到
            <code class="font-mono">advertiser_id / access_token / refresh_token</code> 三件套。官方文档：
            <a href="https://mapi.xiaohongshu.com" target="_blank" class="text-primary-500">https://mapi.xiaohongshu.com</a>。
            access_token 通常 2 小时过期，refresh_token 30 天过期。后端接入后会自动用 refresh_token 刷 access_token。
          </div>
        </NAlert>

        <div class="flex flex-wrap items-center justify-between gap-2">
          <span class="text-13px text-stone-500">共 {{ items.length }} 条聚光凭证</span>
          <NButton size="small" type="primary" @click="openCreate">+ 新增聚光凭证</NButton>
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
            <div class="text-13px font-semibold pb-6px">① 申请应用</div>
            <div class="text-12px leading-relaxed text-stone-600 dark:text-stone-300">
              登录聚光后台 → 开发者中心 → 创建 Marketing 应用，拿到 <code>appId / appSecret</code>。
            </div>
          </NCard>
          <NCard size="small" :bordered="false" content-style="padding: 14px 16px">
            <div class="text-13px font-semibold pb-6px">② 授权回调</div>
            <div class="text-12px leading-relaxed text-stone-600 dark:text-stone-300">
              走 OAuth URL 拿到 <code>code</code>，用 <code>AccessToken</code> 接口换成
              <code>access_token + refresh_token</code>。
            </div>
          </NCard>
          <NCard size="small" :bordered="false" content-style="padding: 14px 16px">
            <div class="text-13px font-semibold pb-6px">③ 填到本页</div>
            <div class="text-12px leading-relaxed text-stone-600 dark:text-stone-300">
              点右上角「+ 新增聚光凭证」，把 advertiserId 和 token 填进去即可，后端自动刷新 / 轮换。
            </div>
          </NCard>
        </div>
      </div>
    </NScrollbar>

    <NModal
      v-model:show="visible"
      preset="dialog"
      :title="editingId ? '编辑聚光凭证' : '新增聚光凭证'"
      :show-icon="false"
      :mask-closable="false"
      class="w-580px!"
    >
      <NForm :model="form" label-placement="left" :label-width="110" mt-10>
        <NFormItem label="广告主 ID">
          <NInput v-model:value="form.advertiserId" placeholder="聚光 advertiserId，数字" />
        </NFormItem>
        <NFormItem label="access_token">
          <NInput
            v-model:value="form.accessToken"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            :placeholder="editingId ? '留空 = 不覆盖现有 token' : '从 OAuth AccessToken 接口拿到'"
          />
        </NFormItem>
        <NFormItem label="refresh_token">
          <NInput
            v-model:value="form.refreshToken"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            :placeholder="editingId ? '留空 = 不覆盖' : '过期后用它换新的 access_token'"
          />
        </NFormItem>
        <NFormItem label="账号备注">
          <NInput v-model:value="form.accountLabel" placeholder="便于区分，例如：甲方-x 品牌" maxlength="64" />
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
