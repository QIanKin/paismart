<script setup lang="tsx">
/**
 * 小红书 · 网页 Cookie（Spider_XHS 通道）面板。
 *
 * 沿用原 /xhs-cookies 页面的 SOP、实时字段检测、平台过滤。
 * 此面板管理 platform ∈ {xhs_pc, xhs_creator, xhs_pgy, xhs_qianfan} 的凭证。
 *
 * 能做的事：
 *  - 粘贴浏览器 DevTools 里的 cookie 字符串 → 实时检测 a1/web_session/webId 是否齐全 → 保存
 *  - 过期时一键覆盖（编辑态留空即不改，粘新串自动覆盖）
 *  - 提供 "一键从浏览器捕获" 书签工具代码（用户在 xiaohongshu.com 标签页运行）
 */
import { computed, h, nextTick, reactive, ref, watch } from 'vue';
import type { DataTableColumns, FormRules, PaginationProps } from 'naive-ui';
import {
  NButton,
  NCard,
  NCode,
  NDataTable,
  NDivider,
  NFlex,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NScrollbar,
  NSelect,
  NSpace,
  NTag
} from 'naive-ui';
import { fetchXhsCookieCreate, fetchXhsCookieDelete, fetchXhsCookieUpdate } from '@/service/api';

interface Props {
  items: Api.Xhs.Cookie[];
}
const props = defineProps<Props>();
const emit = defineEmits<{ (e: 'changed'): void }>();

const REQUIRED_COOKIE_FIELDS = ['a1', 'web_session', 'webId'] as const;
const OPTIONAL_COOKIE_FIELDS = ['xsecappid', 'gid', 'abRequestId'] as const;

function parseCookieKeys(raw: string): Set<string> {
  if (!raw || !raw.trim()) return new Set();
  const sep = raw.includes('; ') ? '; ' : ';';
  const keys = new Set<string>();
  for (const seg of raw.split(sep)) {
    const eq = seg.indexOf('=');
    if (eq > 0) keys.add(seg.slice(0, eq).trim());
  }
  return keys;
}

interface CookieFormModel {
  platform: Api.Xhs.Platform;
  accountLabel: string;
  cookie: string;
  note: string;
  priority: number;
  status: Api.Xhs.Status;
}

const platformLabels: Record<string, string> = {
  xhs_pc: '小红书 PC 主站',
  xhs_creator: '创作者中心',
  xhs_pgy: '蒲公英商单后台',
  xhs_qianfan: '千帆品牌后台'
};

const statusLabels: Record<Api.Xhs.Status, { label: string; type: 'success' | 'error' | 'warning' | 'default' }> = {
  ACTIVE: { label: '可用', type: 'success' },
  EXPIRED: { label: '已过期', type: 'warning' },
  BANNED: { label: '被封禁', type: 'error' },
  DISABLED: { label: '已禁用', type: 'default' }
};

const platformOptions = Object.keys(platformLabels).map(p => ({ label: platformLabels[p], value: p }));
const statusOptions = (Object.keys(statusLabels) as Api.Xhs.Status[]).map(s => ({
  label: statusLabels[s].label,
  value: s
}));

const visible = ref(false);
const submitting = ref(false);
const editingId = ref<number | null>(null);

const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

function createDefaultModel(): CookieFormModel {
  return {
    platform: 'xhs_pc',
    accountLabel: '',
    cookie: '',
    note: '',
    priority: 10,
    status: 'ACTIVE'
  };
}

const model = ref<CookieFormModel>(createDefaultModel());
const isEditing = computed(() => editingId.value !== null);

const detectedCookieKeys = computed(() => parseCookieKeys(model.value.cookie));
const requiredCheck = computed(() =>
  REQUIRED_COOKIE_FIELDS.map(name => ({ name, present: detectedCookieKeys.value.has(name) }))
);
const missingRequired = computed(() => requiredCheck.value.filter(i => !i.present).map(i => i.name));
const optionalPresent = computed(() =>
  OPTIONAL_COOKIE_FIELDS.filter(name => detectedCookieKeys.value.has(name))
);

const rules = ref<FormRules>({
  platform: [defaultRequiredRule],
  cookie: [
    {
      validator: (_: unknown, value: string) => isEditing.value || Boolean(value?.trim()),
      message: '新建时必须粘贴完整 Cookie',
      trigger: ['blur', 'input']
    },
    {
      validator: (_: unknown, value: string) => {
        if (!value?.trim()) return true;
        const keys = parseCookieKeys(value);
        return REQUIRED_COOKIE_FIELDS.every(n => keys.has(n));
      },
      message: `Cookie 缺少：${REQUIRED_COOKIE_FIELDS.join(' / ')}（Spider_XHS 必须）`,
      trigger: ['blur']
    }
  ]
});

// ============ 筛选 & 分页 ============
const filterPlatform = ref<string>('all');
const filterStatus = ref<Api.Xhs.Status | 'all'>('all');

const filtered = computed(() =>
  props.items.filter(c => {
    if (filterPlatform.value !== 'all' && c.platform !== filterPlatform.value) return false;
    if (filterStatus.value !== 'all' && c.status !== filterStatus.value) return false;
    return true;
  })
);

const pagination = reactive<PaginationProps>({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 20, 30, 50],
  onUpdatePage: (p: number) => (pagination.page = p),
  onUpdatePageSize: (s: number) => {
    pagination.pageSize = s;
    pagination.page = 1;
  }
});

watch(filtered, list => {
  pagination.itemCount = list.length;
  const maxPage = Math.max(1, Math.ceil(list.length / Number(pagination.pageSize)));
  if (Number(pagination.page) > maxPage) pagination.page = maxPage;
});

// ============ 表格列 ============
const columns = computed<DataTableColumns<Api.Xhs.Cookie>>(() => [
  {
    key: 'platform',
    title: '平台',
    width: 150,
    render: row => <NTag type="info">{platformLabels[row.platform] || row.platform}</NTag>
  },
  {
    key: 'accountLabel',
    title: '账号备注',
    minWidth: 140,
    render: row => row.accountLabel || <span class="op-40">未命名</span>
  },
  {
    key: 'cookieKeys',
    title: '字段完整性',
    minWidth: 260,
    render: row => {
      const keySet = new Set(
        (row.cookieKeys || '')
          .split(',')
          .map(k => k.trim())
          .filter(Boolean)
      );
      return (
        <div class="flex flex-wrap gap-1">
          {REQUIRED_COOKIE_FIELDS.map(name => (
            <NTag key={name} size="tiny" type={keySet.has(name) ? 'success' : 'error'} bordered={false}>
              {keySet.has(name) ? `✓ ${name}` : `✗ ${name}`}
            </NTag>
          ))}
          {keySet.size > REQUIRED_COOKIE_FIELDS.length && (
            <NTag size="tiny" bordered={false}>
              +{keySet.size - REQUIRED_COOKIE_FIELDS.length} 其他
            </NTag>
          )}
        </div>
      );
    }
  },
  {
    key: 'status',
    title: '状态',
    width: 90,
    render: row => {
      const s = statusLabels[row.status] || statusLabels.DISABLED;
      return <NTag type={s.type}>{s.label}</NTag>;
    }
  },
  {
    key: 'health',
    title: '成功/失败',
    width: 100,
    render: row => (
      <span>
        <span class="text-success">{row.successCount}</span>
        <span class="op-40"> / </span>
        <span class={row.failCount > 0 ? 'text-error' : 'op-40'}>{row.failCount}</span>
      </span>
    )
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
        <NButton type="primary" ghost size="small" onClick={() => openEditDialog(row)}>
          编辑
        </NButton>
        <NPopconfirm onPositiveClick={() => handleDelete(row.id)}>
          {{
            default: () => '删除后正在使用该 cookie 的任务会立即失败，确认继续？',
            trigger: () => (
              <NButton type="error" ghost size="small">
                删除
              </NButton>
            )
          }}
        </NPopconfirm>
      </NSpace>
    )
  }
]);

function openCreateDialog() {
  editingId.value = null;
  model.value = createDefaultModel();
  visible.value = true;
  nextTick(() => restoreValidation());
}

function openEditDialog(row: Api.Xhs.Cookie) {
  editingId.value = row.id;
  model.value = {
    platform: row.platform,
    accountLabel: row.accountLabel ?? '',
    cookie: '',
    note: row.note ?? '',
    priority: row.priority ?? 10,
    status: row.status
  };
  visible.value = true;
  nextTick(() => restoreValidation());
}

async function handleSubmit() {
  await validate();
  submitting.value = true;
  try {
    if (isEditing.value && editingId.value != null) {
      const { error } = await fetchXhsCookieUpdate(editingId.value, {
        cookie: model.value.cookie?.trim() || null,
        accountLabel: model.value.accountLabel?.trim() || null,
        note: model.value.note?.trim() || null,
        priority: model.value.priority ?? null,
        status: model.value.status
      });
      if (!error) {
        window.$message?.success('已保存');
        visible.value = false;
        emit('changed');
      }
    } else {
      const { error } = await fetchXhsCookieCreate({
        platform: model.value.platform,
        cookie: model.value.cookie!.trim(),
        accountLabel: model.value.accountLabel?.trim() || null,
        note: model.value.note?.trim() || null,
        priority: model.value.priority ?? null
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

// ============ 一键捕获书签代码 ============
const bookmarkletCode = computed(() => {
  // 用户在 xiaohongshu.com 标签页按 F12 控制台粘贴运行，自动复制整串 cookie 到剪贴板
  return `(async () => {
  const s = document.cookie;
  await navigator.clipboard.writeText(s);
  alert('已复制 ' + s.length + ' 字节 cookie 到剪贴板，回 PaiSmart 粘贴到「新增凭证」→ Cookie 框。');
})();`;
});
const copyHint = ref('');
async function copyBookmarklet() {
  try {
    await navigator.clipboard.writeText(bookmarkletCode.value);
    copyHint.value = '已复制到剪贴板 ✓';
    setTimeout(() => (copyHint.value = ''), 2500);
  } catch {
    copyHint.value = '复制失败，请手动选中代码';
  }
}
</script>

<template>
  <div class="h-full flex-col gap-10px">
    <NScrollbar style="height: 100%">
      <div class="flex-col gap-12px pb-12px">
        <!-- 捷径卡片 -->
        <NCard
          size="small"
          :bordered="false"
          class="border-dashed b-1 b-stone-200 dark:b-stone-700"
          content-style="padding: 14px 16px"
        >
          <div class="flex items-start gap-3">
            <div class="text-22px">🍪</div>
            <div class="flex-auto flex-col gap-4px">
              <div class="text-14px font-semibold">一键从浏览器捕获 Cookie</div>
              <div class="text-12px text-stone-500 leading-relaxed">
                登录 https://www.xiaohongshu.com → F12 打开控制台 → 粘贴下面这段代码并回车 → 回到本页点「新增凭证」粘贴即可。
              </div>
              <NCode :code="bookmarkletCode" language="javascript" word-wrap class="mt-1" />
              <div class="flex items-center gap-2 pt-1">
                <NButton size="tiny" type="primary" ghost @click="copyBookmarklet">复制代码</NButton>
                <span class="text-xs text-success-500">{{ copyHint }}</span>
              </div>
            </div>
          </div>
        </NCard>

        <!-- 工具栏 -->
        <div class="flex flex-wrap items-center justify-between gap-2">
          <NFlex :size="8" align="center">
            <NSelect
              v-model:value="filterPlatform"
              class="w-48"
              size="small"
              :options="[{ label: '全部平台', value: 'all' }, ...platformOptions]"
            />
            <NSelect
              v-model:value="filterStatus"
              class="w-32"
              size="small"
              :options="[{ label: '全部状态', value: 'all' }, ...statusOptions]"
            />
          </NFlex>
          <NButton size="small" type="primary" @click="openCreateDialog">+ 新增凭证</NButton>
        </div>

        <!-- 表格 -->
        <NDataTable
          :columns="columns"
          :data="filtered"
          size="small"
          :scroll-x="1100"
          :row-key="row => row.id"
          :pagination="pagination"
        />

        <!-- SOP 文档 -->
        <NDivider>凭证获取 SOP</NDivider>
        <div class="grid grid-cols-1 gap-12px md:grid-cols-2">
          <NCard size="small" :bordered="false" content-style="padding: 14px 16px">
            <div class="text-13px font-semibold pb-6px">📖 手动粘贴流程</div>
            <ol class="list-decimal space-y-1 pl-20px text-12px leading-relaxed">
              <li>Chrome/Edge 里登录 <code class="font-mono">https://www.xiaohongshu.com</code></li>
              <li>F12 → Application / 应用 → 左侧 Cookies → 选 <code class="font-mono">xiaohongshu.com</code></li>
              <li>
                必填：
                <NTag size="tiny" type="success" class="mx-1">a1</NTag>
                <NTag size="tiny" type="success" class="mx-1">web_session</NTag>
                <NTag size="tiny" type="success" class="mx-1">webId</NTag>
                可选：<code class="font-mono">xsecappid / gid / abRequestId</code>
              </li>
              <li>拼成 <code class="font-mono">k1=v1; k2=v2; ...</code> 粘到表单</li>
              <li>Cookie 通常 7~30 天失效，过期表格标红，重新覆盖即可</li>
            </ol>
          </NCard>
          <NCard size="small" :bordered="false" content-style="padding: 14px 16px">
            <div class="text-13px font-semibold pb-6px">🤖 Agent 会怎么用这些 Cookie</div>
            <div class="text-12px leading-relaxed text-stone-600 dark:text-stone-300">
              <p class="mb-2">
                当你在会话里让 agent 调用 <code class="font-mono">xhs_refresh_creator</code> /
                <code class="font-mono">use_skill(xhs-user-notes)</code> /
                <code class="font-mono">creator_search</code> 等工具时，后端会按「priority 高、成功率高、最近没用过」
                的策略从池里挑一条 cookie 塞给 Spider_XHS 脚本，调用结束会更新成功/失败计数。
              </p>
              <p>连续失败 5 次会自动 <NTag size="tiny" type="warning" class="mx-1">EXPIRED</NTag>，请及时覆盖。</p>
            </div>
          </NCard>
        </div>
      </div>
    </NScrollbar>

    <!-- 新增/编辑对话框 -->
    <NModal
      v-model:show="visible"
      preset="dialog"
      :title="isEditing ? '编辑凭证' : '新增凭证'"
      :show-icon="false"
      :mask-closable="false"
      class="w-600px!"
    >
      <NForm ref="formRef" :model="model" :rules="rules" label-placement="left" :label-width="90" mt-10>
        <NFormItem label="平台" path="platform">
          <NSelect
            v-model:value="model.platform"
            :options="platformOptions"
            :disabled="isEditing"
            placeholder="请选择"
          />
        </NFormItem>
        <NFormItem label="账号备注" path="accountLabel">
          <NInput v-model:value="model.accountLabel" placeholder="便于识别，例如：官号/测试号/矩阵号A" maxlength="64" />
        </NFormItem>
        <NFormItem label="Cookie" path="cookie">
          <div class="w-full flex-col gap-6px">
            <NInput
              v-model:value="model.cookie"
              type="textarea"
              :placeholder="
                isEditing
                  ? '留空 = 不覆盖现有 cookie；粘贴新完整串则覆盖'
                  : '示例：a1=19a3e1ce...; web_session=0400...; webId=06f9b7f7...; xsecappid=xhs-pc-web'
              "
              :autosize="{ minRows: 4, maxRows: 10 }"
            />
            <div v-if="model.cookie?.trim()" class="flex flex-wrap items-center gap-1 text-12px">
              <span class="op-60">字段检测：</span>
              <NTag
                v-for="item in requiredCheck"
                :key="item.name"
                size="tiny"
                :type="item.present ? 'success' : 'error'"
                :bordered="false"
              >
                {{ item.present ? '✓' : '✗' }} {{ item.name }}
              </NTag>
              <NTag
                v-for="name in optionalPresent"
                :key="`opt-${name}`"
                size="tiny"
                type="info"
                :bordered="false"
              >
                + {{ name }}
              </NTag>
              <span class="op-60">· 共 {{ detectedCookieKeys.size }} 个</span>
            </div>
            <div
              v-if="model.cookie?.trim() && missingRequired.length"
              class="rd-4px bg-red-50 p-6px text-12px text-red-700 dark:bg-red-900/20 dark:text-red-300"
            >
              还缺：{{ missingRequired.join(' / ') }}。请回 DevTools → Application → Cookies 一并复制。
            </div>
          </div>
        </NFormItem>
        <NFormItem label="优先级" path="priority">
          <NInputNumber v-model:value="model.priority" class="w-full" :min="0" :max="100" :precision="0" />
          <span class="pl-2 text-xs text-stone-400">0~100，数值越大越优先被挑</span>
        </NFormItem>
        <NFormItem label="备注" path="note">
          <NInput v-model:value="model.note" placeholder="可选" maxlength="128" />
        </NFormItem>
        <NFormItem v-if="isEditing" label="状态" path="status">
          <NSelect v-model:value="model.status" :options="statusOptions" />
        </NFormItem>
      </NForm>
      <template #action>
        <NSpace :size="12">
          <NButton @click="visible = false">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="handleSubmit">
            {{ isEditing ? '保存' : '添加' }}
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>
