<script setup>
/**
 * 管理后台 · 举报处理（v0.13）
 *
 * 功能：举报列表（按状态筛选）、处理/忽略并填写处理结果、查看举报对象详情。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAdminReports, handleReport } from '@/api/admin'

const loading = ref(false)
const reports = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 10, status: 0 })

const STATUS_TAG = { 0: 'warning', 1: 'success', 2: 'info' }

const handleDialog = reactive({ visible: false, report: null, action: 1, result: '' })

async function load() {
  loading.value = true
  try {
    const res = await getAdminReports({
      page: query.page,
      size: query.size,
      status: query.status === null || query.status === '' ? undefined : query.status
    })
    reports.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  load()
}

function openHandle(row, action) {
  handleDialog.report = row
  handleDialog.action = action
  handleDialog.result = action === 1 ? '已核实并处理（违规内容已下架 / 已警告当事人）' : '经核实不构成违规，忽略'
  handleDialog.visible = true
}

async function submitHandle() {
  const res = await handleReport({
    reportId: handleDialog.report.id,
    action: handleDialog.action,
    result: handleDialog.result
  })
  ElMessage.success(res.message || '处理完成')
  handleDialog.visible = false
  load()
}

/** 处理举报时顺手下架被举报的商品（常见组合操作） */
async function handleAndOffline(row) {
  openHandle(row, 1)
  handleDialog.result = '举报属实，已下架该商品'
  handleDialog.offlineAfter = true
}

onMounted(load)
</script>

<template>
  <div>
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <el-radio-group v-model="query.status" @change="handleSearch">
          <el-radio-button :value="0">待处理</el-radio-button>
          <el-radio-button :value="1">已处理</el-radio-button>
          <el-radio-button :value="2">已忽略</el-radio-button>
          <el-radio-button :value="null">全部</el-radio-button>
        </el-radio-group>
        <el-button @click="load">刷新</el-button>
        <span class="tip">举报由用户在商品详情页或用户主页提交</span>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="reports" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="举报对象" min-width="220">
          <template #default="{ row }">
            <div class="target">
              <el-tag size="small" :type="row.targetType === 1 ? 'primary' : 'warning'">
                {{ row.targetTypeLabel }}
              </el-tag>
              <span class="target-title">{{ row.targetTitle }}</span>
            </div>
            <div class="sub">{{ row.targetExtra }}</div>
          </template>
        </el-table-column>
        <el-table-column label="举报原因" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="danger" effect="plain">{{ row.reasonLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="补充说明" min-width="200">
          <template #default="{ row }">
            <span class="sub">{{ row.content || '（无）' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="举报人" width="130">
          <template #default="{ row }">
            <div>{{ row.reporterName || '-' }}</div>
            <div class="sub">{{ String(row.createTime || '').slice(5, 16) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="STATUS_TAG[row.status]">{{ row.statusLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处理情况" min-width="180">
          <template #default="{ row }">
            <template v-if="row.status === 0">
              <span class="sub">待处理</span>
            </template>
            <template v-else>
              <div class="sub">{{ row.handleResult }}</div>
              <div class="sub">{{ row.handleAdminName }} · {{ String(row.handleTime || '').slice(5, 16) }}</div>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 0">
              <el-button size="small" type="success" @click="openHandle(row, 1)">处理</el-button>
              <el-button size="small" @click="openHandle(row, 2)">忽略</el-button>
              <el-button v-if="row.targetType === 1" size="small" type="warning"
                         @click="$router.push(`/product/${row.targetId}`)">看商品</el-button>
            </template>
            <span v-else class="sub">已结束</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination background layout="total, prev, pager, next, jumper" :total="total"
                       :current-page="query.page" :page-size="query.size"
                       @current-change="(p) => { query.page = p; load() }" />
      </div>
    </el-card>

    <el-dialog v-model="handleDialog.visible"
               :title="handleDialog.action === 1 ? '处理举报' : '忽略举报'" width="520px">
      <div v-if="handleDialog.report">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="举报对象">
            {{ handleDialog.report.targetTypeLabel }}：{{ handleDialog.report.targetTitle }}
          </el-descriptions-item>
          <el-descriptions-item label="举报原因">{{ handleDialog.report.reasonLabel }}</el-descriptions-item>
          <el-descriptions-item label="补充说明">{{ handleDialog.report.content || '（无）' }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="80px" class="handle-form">
          <el-form-item label="处理结果">
            <el-input v-model="handleDialog.result" type="textarea" :rows="3" maxlength="255" show-word-limit />
          </el-form-item>
        </el-form>
        <el-alert v-if="handleDialog.report.targetType === 1 && handleDialog.action === 1" type="info"
                  :closable="false" show-icon
                  title="提示：若确认商品违规，请到「商品管理」执行强制下架（可填写下架原因通知卖家）。" />
      </div>
      <template #footer>
        <el-button @click="handleDialog.visible = false">取消</el-button>
        <el-button :type="handleDialog.action === 1 ? 'success' : 'info'" @click="submitHandle">
          确认{{ handleDialog.action === 1 ? '处理' : '忽略' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 14px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.tip {
  margin-left: auto;
  font-size: 12px;
  color: var(--ct-text-muted);
}

.target {
  display: flex;
  align-items: center;
  gap: 8px;
}

.target-title {
  font-weight: 600;
  font-size: 13px;
}

.sub {
  font-size: 12px;
  color: var(--ct-text-muted);
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 14px;
}

.handle-form {
  margin-top: 12px;
}
</style>
