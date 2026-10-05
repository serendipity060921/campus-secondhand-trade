<script setup>
/**
 * 管理后台 · 数据看板（v0.13）
 *
 * 三块内容：
 *   ① 概览卡片：用户/商品/订单/成交额/今日新增/待办
 *   ② 近 7 天趋势折线（用户、商品、订单、成交额 双 Y 轴）
 *   ③ 分类分布饼图 + 订单/商品状态环形图
 *
 * 图表用 ECharts 按需引入（只注册用到的图表与组件），避免整包体积。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getDashboard } from '@/api/admin'
import { chartPalette } from '@/utils/design-tokens'
import StateError from '@/components/states/StateError.vue'

echarts.use([LineChart, PieChart, BarChart, GridComponent, TooltipComponent,
  LegendComponent, TitleComponent, CanvasRenderer])

const router = useRouter()
const loading = ref(false)
const loadError = ref(false)
const overview = ref({})
const generatedAt = ref('')

const trendRef = ref()
const categoryRef = ref()
const orderStatusRef = ref()
const productStatusRef = ref()

let charts = []

const money = (v) => `￥${Number(v || 0).toFixed(2)}`

function initChart(el, option) {
  if (!el) {
    return
  }
  const chart = echarts.init(el)
  chart.setOption(option)
  charts.push(chart)
  return chart
}

function renderTrend(data) {
  const days = data.userTrend.map((i) => i.label)
  const gmv = data.orderTrend.map((i) => Number(i.amount || 0))
  initChart(trendRef.value, {
    tooltip: { trigger: 'axis' },
    legend: { data: ['新增用户', '新增商品', '新增订单', '成交额'], bottom: 0 },
    grid: { left: 45, right: 60, top: 30, bottom: 45 },
    xAxis: { type: 'category', data: days, axisLabel: { fontSize: 11 } },
    yAxis: [
      { type: 'value', name: '数量', minInterval: 1 },
      { type: 'value', name: '金额(元)' }
    ],
    series: [
      { name: '新增用户', type: 'line', smooth: true, data: data.userTrend.map((i) => i.value), itemStyle: { color: chartPalette.category()[1] } },
      { name: '新增商品', type: 'line', smooth: true, data: data.productTrend.map((i) => i.value), itemStyle: { color: chartPalette.category()[2] } },
      { name: '新增订单', type: 'line', smooth: true, data: data.orderTrend.map((i) => i.value), itemStyle: { color: chartPalette.warning() } },
      { name: '成交额', type: 'bar', yAxisIndex: 1, data: gmv, barWidth: 14, itemStyle: { color: chartPalette.ink(), opacity: 0.75 } }
    ]
  })
}

function renderPie(el, title, data, colors) {
  initChart(el, {
    title: { text: title, left: 'center', top: 0, textStyle: { fontSize: 13, color: chartPalette.muted() } },
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    // 图例给出完整名称；环图**不再画外置标签** —— 两者同时出现时，
    // 在后台这些窄卡片里（尤其"订单状态分布/商品状态分布"）标签会互相压叠。
    legend: { bottom: 0, type: 'scroll', textStyle: { fontSize: 11 }, itemWidth: 8, itemHeight: 8 },
    color: colors,
    series: [{
      type: 'pie',
      radius: ['28%', '48%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      minAngle: 3,                                   // 极小占比的扇区也保留可见角度，避免"信息只剩颜色"
      label: { show: false },
      labelLine: { show: false },
      data: data.map((i) => ({ name: i.label, value: i.value }))
    }]
  })
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const res = await getDashboard()
    const data = res.data
    overview.value = data.overview
    generatedAt.value = data.generatedAt
    // 等待 DOM 渲染后再初始化图表
    setTimeout(() => {
      disposeCharts()
      renderTrend(data)
      renderPie(categoryRef.value, '商品分类分布', data.categoryDist, chartPalette.category())
      renderPie(orderStatusRef.value, '订单状态分布', data.orderStatusDist,
        [chartPalette.trading(), chartPalette.success(), chartPalette.sold()])
      renderPie(productStatusRef.value, '商品状态分布', data.productStatusDist,
        [chartPalette.danger(), chartPalette.success(), chartPalette.warning(),
          chartPalette.sold(), chartPalette.ink()])
    }, 50)
  } catch (e) {
    loadError.value = true
   } finally {
    loading.value = false
  }
}

function disposeCharts() {
  charts.forEach((c) => c.dispose())
  charts = []
}

function handleResize() {
  charts.forEach((c) => c.resize())
}

onMounted(() => {
  load()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  disposeCharts()
})
</script>

<template>
  <!-- 页面主标题（后台看板） -->
  <h1 class="sr-only">数据看板</h1>
  <StateError
    v-if="loadError && !loading"
    title="加载失败，请稍后重试"
    detail="网络可能不稳定，或服务正在重启"
    retry-text="重新加载"
    @retry="load"
  />
  <div v-else v-loading="loading">
    <!-- 待办提醒 -->
    <el-alert v-if="overview.pendingAuditCount || overview.pendingReportCount" type="warning"
              :closable="false" show-icon class="todo-alert">
      <template #title>
        待办事项：
        <el-link v-if="overview.pendingAuditCount" type="primary" @click="router.push('/admin/products')">
          {{ overview.pendingAuditCount }} 件商品待审核
        </el-link>
        <span v-if="overview.pendingAuditCount && overview.pendingReportCount"> · </span>
        <el-link v-if="overview.pendingReportCount" type="primary" @click="router.push('/admin/reports')">
          {{ overview.pendingReportCount }} 条举报待处理
        </el-link>
      </template>
    </el-alert>

    <!-- 概览卡片 -->
    <el-row :gutter="14">
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">用户总数</div>
          <div class="stat-value">{{ overview.userCount || 0 }}</div>
          <div class="stat-sub">今日 +{{ overview.todayNewUsers || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">商品总数</div>
          <div class="stat-value">{{ overview.productCount || 0 }}</div>
          <div class="stat-sub">在售 {{ overview.onSaleCount || 0 }} · 今日 +{{ overview.todayNewProducts || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">订单总数</div>
          <div class="stat-value">{{ overview.orderCount || 0 }}</div>
          <div class="stat-sub">已完成 {{ overview.finishedOrderCount || 0 }} · 今日 +{{ overview.todayNewOrders || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">累计成交额</div>
          <div class="stat-value price">{{ money(overview.gmv) }}</div>
          <div class="stat-sub">近 7 天 {{ money(overview.gmv7d) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">待审核商品</div>
          <div class="stat-value warn">{{ overview.pendingAuditCount || 0 }}</div>
          <div class="stat-sub">
            <el-link type="primary" @click="router.push('/admin/products')">去审核</el-link>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">待处理举报</div>
          <div class="stat-value warn">{{ overview.pendingReportCount || 0 }}</div>
          <div class="stat-sub">
            <el-link type="primary" @click="router.push('/admin/reports')">去处理</el-link>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 趋势 -->
    <el-card shadow="never" class="chart-card">
      <template #header>
        <div class="card-header">
          <b>近 7 天趋势</b>
          <span class="text-muted">数据截至 {{ generatedAt }}</span>
        </div>
      </template>
      <div ref="trendRef" class="chart chart-lg" />
    </el-card>

    <!-- 分布 -->
    <el-row :gutter="14">
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="chart-card">
          <div ref="categoryRef" class="chart" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="6">
        <el-card shadow="never" class="chart-card">
          <div ref="orderStatusRef" class="chart" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="6">
        <el-card shadow="never" class="chart-card">
          <div ref="productStatusRef" class="chart" />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <el-descriptions :column="4" border size="small" title="其他数据">
        <el-descriptions-item label="私信/留言总数">{{ overview.messageCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="收藏总数">{{ overview.favoriteCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="在售商品占比">
          {{ overview.productCount ? Math.round((overview.onSaleCount / overview.productCount) * 100) : 0 }}%
        </el-descriptions-item>
        <el-descriptions-item label="订单完成率">
          {{ overview.orderCount ? Math.round((overview.finishedOrderCount / overview.orderCount) * 100) : 0 }}%
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<style scoped>
.todo-alert {
  margin-bottom: var(--ct-space-4);
}

.stat-card {
  margin-bottom: var(--ct-space-4);
  text-align: center;
}

.stat-label {
  font-size: var(--ct-text-sm);
  color: var(--ct-text-muted);
}

.stat-value {
  font-size: var(--ct-text-2xl);
  font-weight: 700;
  color: var(--ct-text-primary);
  margin: var(--ct-space-1) 0 var(--ct-space-1);
}

.stat-value.price {
  color: var(--ct-price);
  font-size: var(--ct-text-xl);
}

.stat-value.warn {
  color: var(--el-color-warning);
}

.stat-sub {
  font-size: 12px;
  color: var(--ct-text-muted);
  min-height: 20px;
}

.chart-card {
  margin-bottom: 14px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chart {
  width: 100%;
  height: 340px;         /* 环形图标签与图例需要更多竖直空间，原先 300px 时标签易被省略 */
}

.chart-lg {
  height: 340px;
}

.text-muted {
  color: var(--ct-text-muted);
  font-size: 12px;
}
</style>
