<script setup>
/**
 * 管理后台 · 用户管理（v0.13）
 *
 * 功能：用户列表（关键词/角色/状态筛选）、启用/禁用、查看发布与成交统计。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { changeUserStatus, getAdminUsers } from '@/api/admin'

const loading = ref(false)
const users = ref([])
const total = ref(0)

const query = reactive({ page: 1, size: 10, keyword: '', role: null, status: null })

async function load() {
  loading.value = true
  try {
    const res = await getAdminUsers({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      role: query.role === null || query.role === '' ? undefined : query.role,
      status: query.status === null || query.status === '' ? undefined : query.status
    })
    users.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  load()
}

async function toggleStatus(row) {
  const disable = row.status === 1
  try {
    await ElMessageBox.confirm(
      disable
        ? `确定禁用「${row.nickname || row.username}」吗？禁用后该账号将无法登录。`
        : `确定启用「${row.nickname || row.username}」吗？`,
      disable ? '禁用用户' : '启用用户',
      { type: 'warning' }
    )
    const res = await changeUserStatus({ userId: row.id, status: disable ? 0 : 1 })
    ElMessage.success(disable ? '已禁用该账号' : '已启用该账号')
    row.status = res.data.status
    row.statusLabel = res.data.statusLabel
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
        <el-input v-model="query.keyword" placeholder="用户名 / 昵称 / 学号 / 手机号" clearable
                  class="keyword" @keyup.enter="handleSearch" />
        <el-select v-model="query.role" placeholder="全部角色" clearable class="role" @change="handleSearch">
          <el-option label="学生" :value="0" />
          <el-option label="管理员" :value="1" />
        </el-select>
        <el-select v-model="query.status" placeholder="全部状态" clearable class="status" @change="handleSearch">
          <el-option label="正常" :value="1" />
          <el-option label="已禁用" :value="0" />
        </el-select>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="query.keyword = ''; query.role = null; query.status = null; handleSearch()">重置</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="users" border stripe>
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="34" :src="row.avatar || undefined">
                {{ (row.nickname || row.username || '?').slice(0, 1) }}
              </el-avatar>
              <div>
                <div class="name">{{ row.nickname || '-' }}</div>
                <div class="sub">@{{ row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="联系方式" width="190">
          <template #default="{ row }">
            <div class="sub">{{ row.phone || '未绑定手机号' }}</div>
            <div class="sub">{{ row.email || '未绑定邮箱' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="校区 / 学校" width="150">
          <template #default="{ row }">
            <div class="sub">{{ row.campus || '-' }}</div>
            <div class="sub">{{ row.school || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.role === 1 ? 'danger' : 'info'">{{ row.roleLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="信用分" width="90" align="center" prop="creditScore" />
        <el-table-column label="发布/成交/被举报" width="150" align="center">
          <template #default="{ row }">
            <span class="sub">{{ row.productCount }} / {{ row.orderCount }} / {{ row.reportCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'">{{ row.statusLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="150">
          <template #default="{ row }">{{ String(row.createTime || '').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :type="row.status === 1 ? 'danger' : 'success'"
                       :disabled="row.role === 1" @click="toggleStatus(row)">
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination background layout="total, prev, pager, next, jumper" :total="total"
                       :current-page="query.page" :page-size="query.size"
                       @current-change="(p) => { query.page = p; load() }" />
      </div>
      <el-alert type="info" :closable="false" show-icon class="tip-alert"
                title="说明：管理员账号不可被禁用（避免误操作把自己或其它管理员锁在系统外）；禁用后该账号无法登录，但历史商品与订单仍然保留。" />
    </el-card>
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

.role,
.status {
  width: 140px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.name {
  font-weight: 600;
  font-size: 13px;
}

.sub {
  font-size: 12px;
  color: #909399;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 14px;
}

.tip-alert {
  margin-top: 12px;
}
</style>
