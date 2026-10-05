<script setup>
/**
 * 管理后台 · 商品管理（v0.13）
 *
 * 功能：全状态商品列表（筛选/分页）、商品审核（通过 / 驳回并填写理由）、强制下架。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auditProduct, getAdminProducts, offlineProduct } from '@/api/admin'
import { formatPrice, resolveImageUrl } from '@/utils/product'
import SkeletonTable from '@/components/states/SkeletonTable.vue'
import StateEmpty from '@/components/states/StateEmpty.vue'
import StateError from '@/components/states/StateError.vue'

const loading = ref(false)
const loadError = ref(false)
const products = ref([])
const total = ref(0)

const query = reactive({
  page: 1,
  size: 10,
  status: 0,
  keyword: ''
})

const STATUS_OPTIONS = [
  { value: null, label: '全部状态' },
  { value: 0, label: '待审核' },
  { value: 1, label: '在售' },
  { value: 2, label: '审核不通过' },
  { value: 3, label: '已下架' },
  { value: 4, label: '交易中' },
  { value: 5, label: '已售出' }
]

const STATUS_TAG = { 0: 'warning', 1: 'success', 2: 'danger', 3: 'info', 4: 'primary', 5: 'info' }

const auditDialog = reactive({ visible: false, product: null, approve: true, remark: '' })

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const res = await getAdminProducts({
      page: query.page,
      size: query.size,
      status: query.status === null || query.status === '' ? undefined : query.status,
      keyword: query.keyword || undefined
    })
    products.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  load()
}

function openAudit(row, approve) {
  auditDialog.product = row
  auditDialog.approve = approve
  auditDialog.remark = approve ? '资料完整，审核通过' : ''
  auditDialog.visible = true
}

async function submitAudit() {
  if (!auditDialog.approve && !auditDialog.remark.trim()) {
    ElMessage.warning('驳回时请填写驳回理由，方便卖家修改')
    return
  }
  const res = await auditProduct({
    productId: auditDialog.product.id,
    approve: auditDialog.approve,
    remark: auditDialog.remark
  })
  ElMessage.success(res.message || '审核完成')
  auditDialog.visible = false
  load()
}

async function handleOffline(row) {
  try {
    const { value } = await ElMessageBox.prompt('请输入下架原因（会展示给卖家）', '强制下架', {
      inputPlaceholder: '例如：涉嫌违规商品，请整改后重新发布',
      inputValidator: (v) => (v && v.trim() ? true : '下架原因不能为空'),
      type: 'warning'
    })
    const res = await offlineProduct(row.id, value)
    ElMessage.success(res.message || '已下架')
    load()
  } catch (e) {
    /* 取消 */
  }
}

onMounted(load)
</script>

<template>
  <div>
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <el-input v-model="query.keyword" placeholder="搜索商品名称" clearable class="keyword"
                  @keyup.enter="handleSearch" />
        <el-select v-model="query.status" class="status" @change="handleSearch">
          <el-option v-for="item in STATUS_OPTIONS" :key="String(item.value)"
                     :label="item.label" :value="item.value" />
        </el-select>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="query.keyword = ''; handleSearch()">重置</el-button>
        <span class="tip">待审核商品需要审核通过后才会上架</span>
      </div>
    </el-card>

    <el-card shadow="never">
      <SkeletonTable v-if="loading" :rows="8" :columns="6" />
      <StateError
        v-else-if="loadError"
        title="列表加载失败"
        detail="请检查网络后重试，或稍后再来"
        retry-text="重新加载"
        @retry="load"
      />
      <el-table v-else :data="products" border stripe>
        <template #empty>
          <StateEmpty title="没有符合条件的商品" hint="换个状态或关键词再试，或去前台看看在售商品" action-text="去前台看看" action-to="/home" />
        </template>
        <el-table-column label="商品" min-width="240">
          <template #default="{ row }">
            <div class="product-cell">
              <img :src="row.coverImage ? resolveImageUrl(row.coverImage) : '/demo-images/default.png'"
                   class="thumb" alt="封面" />
              <div class="meta">
                <div class="title">{{ row.title }}</div>
                <div class="sub">
                  {{ row.categoryName || '未分类' }} · {{ row.campus || '未填校区' }}
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="价格" width="100">
          <template #default="{ row }">
            <span class="price">{{ formatPrice(row.price) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="卖家" width="140">
          <template #default="{ row }">
            <div>{{ row.sellerNickname || '-' }}</div>
            <div class="sub">{{ row.sellerUsername }} · 信用 {{ row.sellerCreditScore }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status] || 'info'" size="small">{{ row.statusLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核信息" width="180">
          <template #default="{ row }">
            <div v-if="row.auditorName" class="sub">
              {{ row.auditorName }} · {{ String(row.auditTime || '').slice(5, 16) }}
            </div>
            <div v-if="row.auditRemark" class="sub remark" :title="row.auditRemark">{{ row.auditRemark }}</div>
            <span v-if="!row.auditorName" class="sub">-</span>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="150">
          <template #default="{ row }">{{ String(row.createTime || '').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 0">
              <el-button size="small" type="success" @click="openAudit(row, true)">通过</el-button>
              <el-button size="small" type="danger" @click="openAudit(row, false)">驳回</el-button>
            </template>
            <template v-else>
              <el-button size="small" @click="$router.push(`/product/${row.id}`)">查看</el-button>
              <el-button v-if="row.status === 1" size="small" type="warning"
                         @click="handleOffline(row)">下架</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination background layout="total, prev, pager, next, jumper" :total="total"
                       :current-page="query.page" :page-size="query.size"
                       @current-change="(p) => { query.page = p; load() }" />
      </div>
    </el-card>

    <!-- 审核弹窗 -->
    <el-dialog v-model="auditDialog.visible" :title="auditDialog.approve ? '审核通过' : '驳回商品'" width="520px">
      <div v-if="auditDialog.product" class="audit-body">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="商品">{{ auditDialog.product.title }}</el-descriptions-item>
          <el-descriptions-item label="价格">{{ formatPrice(auditDialog.product.price) }}</el-descriptions-item>
          <el-descriptions-item label="描述">
            {{ auditDialog.product.description || '（无描述）' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-form label-width="80px" class="audit-form">
          <el-form-item :label="auditDialog.approve ? '审核意见' : '驳回理由'">
            <el-input v-model="auditDialog.remark" type="textarea" :rows="3" maxlength="255" show-word-limit
                      :placeholder="auditDialog.approve ? '例如：资料完整，审核通过' : '请说明驳回原因，卖家会看到'" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="auditDialog.visible = false">取消</el-button>
        <el-button :type="auditDialog.approve ? 'success' : 'danger'" @click="submitAudit">
          确认{{ auditDialog.approve ? '通过' : '驳回' }}
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
  gap: 10px;
  flex-wrap: wrap;
}

.keyword {
  width: 240px;
}

.status {
  width: 150px;
}

.tip {
  margin-left: auto;
  font-size: 12px;
  color: var(--ct-text-muted);
}

.product-cell {
  display: flex;
  gap: 10px;
  align-items: center;
}

.thumb {
  width: 46px;
  height: 46px;
  object-fit: cover;
  border-radius: 4px;
  background: var(--ct-bg-canvas);
}

.title {
  font-weight: 600;
  font-size: 13px;
}

.sub {
  font-size: 12px;
  color: var(--ct-text-muted);
}

.remark {
  color: var(--el-color-warning);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.price {
  color: var(--ct-price);
  font-weight: 700;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 14px;
}

.audit-form {
  margin-top: 12px;
}
</style>
