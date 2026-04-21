<script setup lang="tsx">
import type { DataTableColumns, FormRules, PaginationProps } from 'naive-ui';
import { NButton, NInput, NInputNumber, NPopconfirm, NSelect, NTag } from 'naive-ui';
import { fetchXhsCookieCreate, fetchXhsCookieDelete, fetchXhsCookieList, fetchXhsCookieUpdate } from '@/service/api';
import QrLoginModal from './modules/qr-login-modal.vue';

// Spider_XHS 签名逻辑硬依赖 a1，其他两个是实战中高频被服务端校验的。
// 参考：Spider_XHS/xhs_utils/xhs_util.py -> generate_request_params 里 cookies['a1']
const REQUIRED_COOKIE_FIELDS = ['a1', 'web_session', 'webId'] as const;
const OPTIONAL_COOKIE_FIELDS = ['xsecappid', 'gid', 'abRequestId'] as const;
type RequiredCookieField = (typeof REQUIRED_COOKIE_FIELDS)[number];

/** 从 "k1=v1; k2=v2" 或 "k1=v1;k2=v2" 的 Cookie 字符串里解析出 key 集合，用来做完整性检测。 */
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

const appStore = useAppStore();

interface CookieFormModel {
  platform: Api.Xhs.Platform;
  accountLabel: string;
  cookie: string;
  note: string;
  priority: number;
  status: Api.Xhs.Status;
}

const platformLabels: Record<Api.Xhs.Platform, string> = {
  xhs_pc: '小红书 PC',
  xhs_creator: '小红书创作者中心',
  xhs_pgy: '蒲公英（商单后台）',
  xhs_qianfan: '千帆（品牌方后台）',
  xhs_spotlight: '聚光 Marketing API',
  xhs_competitor: '竞品笔记监控'
};

const statusLabels: Record<Api.Xhs.Status, { label: string; type: 'success' | 'error' | 'warning' | 'default' }> = {
  ACTIVE: { label: '可用', type: 'success' },
  EXPIRED: { label: '已过期', type: 'warning' },
  BANNED: { label: '被封禁', type: 'error' },
  DISABLED: { label: '已禁用', type: 'default' }
};

const platformOptions = computed(() =>
  (Object.keys(platformLabels) as Api.Xhs.Platform[]).map(p => ({ label: platformLabels[p], value: p }))
);
const statusOptions = computed(() =>
  (Object.keys(statusLabels) as Api.Xhs.Status[]).map(s => ({ label: statusLabels[s].label, value: s }))
);

const loading = ref(false);
const visible = ref(false);
const submitting = ref(false);
const editingId = ref<number | null>(null);
const qrLoginVisible = ref(false);

const allItems = ref<Api.Xhs.Cookie[]>([]);
const insecureDefault = ref(false);
const filterPlatform = ref<Api.Xhs.Platform | 'all'>('all');
const filterStatus = ref<Api.Xhs.Status | 'all'>('all');
/** 来自后端 /admin/xhs-cookies list 接口的 requiredFields，兜底用常量。 */
const requiredFields = ref<string[]>([...REQUIRED_COOKIE_FIELDS]);

const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

const model = ref<CookieFormModel>(createDefaultModel());
const isEditing = computed(() => editingId.value !== null);

// 实时解析 model.cookie，得到 a1/web_session/webId 的检出状态；编辑态留空则展示"未改动"。
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
      // 只有新建或用户主动粘了新值时才校验完整性，编辑态留空（表示不改）直接放行。
      validator: (_: unknown, value: string) => {
        if (!value?.trim()) return true;
        const keys = parseCookieKeys(value);
        return REQUIRED_COOKIE_FIELDS.every(n => keys.has(n));
      },
      message: `Cookie 字符串缺少关键字段：${REQUIRED_COOKIE_FIELDS.join(' / ')}（Spider_XHS 最少需要这三个）`,
      trigger: ['blur']
    }
  ],
  priority: [
    {
      validator: (_: unknown, value: number) => value !== null && value !== undefined && value >= 0 && value <= 100,
      message: '优先级需要在 0-100 之间，数值越小越优先',
      trigger: 'change'
    }
  ]
});

const filtered = computed(() =>
  allItems.value.filter(c => {
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
  onUpdatePage: (page: number) => {
    pagination.page = page;
  },
  onUpdatePageSize: (pageSize: number) => {
    pagination.pageSize = pageSize;
    pagination.page = 1;
  }
});

watch(filtered, list => {
  pagination.itemCount = list.length;
  const maxPage = Math.max(1, Math.ceil(list.length / Number(pagination.pageSize)));
  if (Number(pagination.page) > maxPage) pagination.page = maxPage;
});

const columns = computed<DataTableColumns<Api.Xhs.Cookie>>(() => [
  {
    key: 'platform',
    title: '平台',
    width: 160,
    render: row => <NTag type="info">{platformLabels[row.platform] || row.platform}</NTag>
  },
  {
    key: 'accountLabel',
    title: '账号备注',
    minWidth: 160,
    render: row => row.accountLabel || <span class="op-40">未命名</span>
  },
  {
    key: 'cookiePreview',
    title: 'Cookie 预览 / 字段完整性',
    minWidth: 280,
    render: row => {
      // 后端落库时把明文 cookie 里的 key 名字提取出来存在 cookieKeys 里（不含 value）。
      // 这里直接用后端权威数据，而不是前端解析 cookiePreview（48 字节会漏）。
      const keySet = new Set((row.cookieKeys || '').split(',').map(k => k.trim()).filter(Boolean));
      return (
        <div class="flex-col gap-2px">
          <span class="text-12px font-mono op-70">{row.cookiePreview ? `${row.cookiePreview}…` : '（已加密存储）'}</span>
          <div class="flex flex-wrap gap-1">
            {(requiredFields.value.length ? requiredFields.value : REQUIRED_COOKIE_FIELDS).map(name => (
              <NTag key={name} size="tiny" type={keySet.has(name) ? 'success' : 'error'} bordered={false}>
                {keySet.has(name) ? `✓ ${name}` : `✗ ${name}`}
              </NTag>
            ))}
            {keySet.size > REQUIRED_COOKIE_FIELDS.length && (
              <NTag size="tiny" type="default" bordered={false}>
                +{keySet.size - REQUIRED_COOKIE_FIELDS.length} 其他
              </NTag>
            )}
          </div>
        </div>
      );
    }
  },
  {
    key: 'status',
    title: '状态',
    width: 100,
    render: row => {
      const s = statusLabels[row.status] || statusLabels.DISABLED;
      return <NTag type={s.type}>{s.label}</NTag>;
    }
  },
  {
    key: 'priority',
    title: '优先级',
    width: 88,
    render: row => row.priority
  },
  {
    key: 'health',
    title: '成功 / 失败',
    width: 110,
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
    render: row => (row.lastUsedAt ? dayjs(row.lastUsedAt).format('YYYY-MM-DD HH:mm') : '—')
  },
  {
    key: 'lastError',
    title: '最近错误',
    minWidth: 160,
    render: row => row.lastError || <span class="op-40">—</span>
  },
  {
    key: 'operate',
    title: '操作',
    width: 220,
    fixed: 'right',
    render: row => (
      <div class="flex items-center gap-2">
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
      </div>
    )
  }
]);

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

async function getData() {
  loading.value = true;
  const { data: payload, error } = await fetchXhsCookieList();
  if (!error && payload) {
    allItems.value = payload.items || [];
    insecureDefault.value = Boolean(payload.insecureDefault);
    if (Array.isArray(payload.requiredFields) && payload.requiredFields.length) {
      requiredFields.value = payload.requiredFields;
    }
    pagination.itemCount = filtered.value.length;
  }
  loading.value = false;
}

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

function closeDialog() {
  visible.value = false;
  editingId.value = null;
}

async function handleSubmit() {
  await validate();
  submitting.value = true;
  const baseBody: Api.Xhs.CookieUpdatePayload = {
    accountLabel: model.value.accountLabel?.trim() || null,
    note: model.value.note?.trim() || null,
    priority: model.value.priority ?? null
  };
  if (model.value.cookie?.trim()) baseBody.cookie = model.value.cookie.trim();

  let error;
  if (isEditing.value && editingId.value != null) {
    const res = await fetchXhsCookieUpdate(editingId.value, {
      ...baseBody,
      status: model.value.status
    });
    error = res.error;
  } else {
    const res = await fetchXhsCookieCreate({
      platform: model.value.platform,
      cookie: baseBody.cookie!,
      accountLabel: baseBody.accountLabel ?? null,
      note: baseBody.note ?? null,
      priority: baseBody.priority ?? null
    });
    error = res.error;
  }

  if (!error) {
    window.$message?.success(isEditing.value ? 'Cookie 已更新' : 'Cookie 已添加');
    closeDialog();
    await getData();
  }
  submitting.value = false;
}

async function handleDelete(id: number) {
  const { error } = await fetchXhsCookieDelete(id);
  if (!error) {
    window.$message?.success('Cookie 已删除');
    await getData();
  }
}

onMounted(() => {
  getData();
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-auto">
    <NCard
      title="小红书 Cookie 池"
      :bordered="false"
      size="small"
      class="sm:flex-1-hidden card-wrapper"
      content-class="flex-col-stretch min-h-0 sm:h-full"
    >
      <template #header-extra>
        <NSpace :size="12" align="center" wrap>
          <NSelect
            v-model:value="filterPlatform"
            class="w-180px"
            :options="[{ label: '全部平台', value: 'all' }, ...platformOptions]"
          />
          <NSelect
            v-model:value="filterStatus"
            class="w-140px"
            :options="[{ label: '全部状态', value: 'all' }, ...statusOptions]"
          />
          <NButton type="primary" @click="qrLoginVisible = true">扫码登录采集</NButton>
          <NButton @click="openCreateDialog">手动录入</NButton>
          <NButton @click="getData">刷新</NButton>
        </NSpace>
      </template>

      <div v-if="insecureDefault" class="mb-3 rd-6px bg-yellow-50 p-3 text-13px text-yellow-800">
        ⚠ 当前后端仍在使用默认的 cookie 加密密钥，生产部署前请在环境变量
        <code class="font-mono">XHS_COOKIE_SECRET</code>
        中配置 ≥ 32 字节的随机字符串。
      </div>

      <div class="mb-3 rd-6px border border-stone-200 bg-stone-50/70 p-12px text-13px leading-relaxed dark:border-stone-700 dark:bg-stone-800/40">
        <div class="mb-2 text-14px font-semibold">采集方式</div>
        <ul class="list-disc space-y-1 pl-20px">
          <li>
            <b>推荐：</b>点右上角
            <NTag type="primary" size="tiny" class="mx-1">扫码登录采集</NTag>
            ——后端自动打开 Chromium，用小红书 App 扫一扫就能批量采到 PC / 创作者 / 蒲公英 / 千帆 四个平台 Cookie。
          </li>
          <li>
            手动 SOP：如果扫码暂时不可用（网络代理 / 风控），可以按下方流程粘贴 Cookie。
          </li>
        </ul>
        <div class="my-2 text-14px font-semibold">手动录入 SOP（兜底）</div>
        <ol class="list-decimal space-y-1 pl-20px">
          <li>Chrome/Edge 里登录 <code class="font-mono">https://www.xiaohongshu.com</code>（普通网页登录即可）。</li>
          <li>F12 打开开发者工具 → <b>Application / 应用</b> → 左侧 <b>Cookies</b> → 选中 <code class="font-mono">https://www.xiaohongshu.com</code>。</li>
          <li>
            这三条是 <b>最低必填</b>（下面编辑框会实时检测）：
            <NTag size="tiny" type="success" class="mx-1">a1</NTag>
            <NTag size="tiny" type="success" class="mx-1">web_session</NTag>
            <NTag size="tiny" type="success" class="mx-1">webId</NTag>
            建议把同域下所有 cookie 一起复制过来（
            <code class="font-mono">xsecappid / gid / abRequestId</code>
            等带上更稳）。
          </li>
          <li>
            拼成 <code class="font-mono">key1=value1; key2=value2; key3=value3</code> 粘进下面的 Cookie 框。
            逗号分号都用英文，Cookie 值里不要手动加空格。
          </li>
          <li>
            保存后后端 AES-GCM 加密入库；Cookie 通常
            <NTag size="tiny" type="warning" class="mx-1">7~30 天</NTag>
            失效，过期时表格会自动标红，按同样流程重新覆盖即可。
          </li>
        </ol>
      </div>

      <div class="min-h-0 sm:flex-1">
        <NDataTable
          :columns="columns"
          :data="filtered"
          size="small"
          :flex-height="!appStore.isMobile"
          :scroll-x="1200"
          :loading="loading"
          :row-key="row => row.id"
          :pagination="pagination"
          class="sm:h-full"
        />
      </div>
    </NCard>

    <NModal
      v-model:show="visible"
      preset="dialog"
      :title="isEditing ? '编辑 Cookie' : '添加 Cookie'"
      :show-icon="false"
      :mask-closable="false"
      class="w-560px!"
    >
      <NForm ref="formRef" :model="model" :rules="rules" label-placement="left" :label-width="100" mt-10>
        <NFormItem label="平台" path="platform">
          <NSelect
            v-model:value="model.platform"
            :options="platformOptions"
            :disabled="isEditing"
            placeholder="请选择平台"
          />
        </NFormItem>
        <NFormItem label="账号备注" path="accountLabel">
          <NInput v-model:value="model.accountLabel" placeholder="便于识别，例如：官号/矩阵号/测试号" maxlength="64" />
        </NFormItem>
        <NFormItem label="Cookie" path="cookie">
          <div class="w-full flex-col gap-6px">
            <NInput
              v-model:value="model.cookie"
              type="textarea"
              :placeholder="
                isEditing
                  ? '留空则不覆盖已有 Cookie（如需更换登录态请粘贴新的完整串）'
                  : '示例：a1=19a3e1cec87s...; web_session=040069...; webId=06f9b7f7...; xsecappid=xhs-pc-web'
              "
              :autosize="{ minRows: 4, maxRows: 10 }"
            />
            <!-- 实时字段检测：只在用户真的粘了东西时出现；空 = 不干扰编辑流 -->
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
            <!-- 新建必须全齐；编辑留空不算错 -->
            <div
              v-if="model.cookie?.trim() && missingRequired.length"
              class="rd-4px bg-red-50 p-6px text-12px text-red-700 dark:bg-red-900/20 dark:text-red-300"
            >
              还缺：{{ missingRequired.join(' / ') }}。请回浏览器 DevTools → Application → Cookies 把这几个一并复制过来。
            </div>
          </div>
        </NFormItem>
        <NFormItem label="优先级" path="priority">
          <NInputNumber v-model:value="model.priority" class="w-full" :min="0" :max="100" :precision="0" />
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
          <NButton @click="closeDialog">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="handleSubmit">
            {{ isEditing ? '保存' : '添加' }}
          </NButton>
        </NSpace>
      </template>
    </NModal>

    <QrLoginModal v-model:show="qrLoginVisible" @success="getData" />
  </div>
</template>

<style scoped></style>
