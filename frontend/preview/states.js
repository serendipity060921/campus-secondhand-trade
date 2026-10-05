/**
 * 状态组件预览（开发用，不进入生产构建）
 *
 * 用途：在真实浏览器里核对状态组件的视觉与响应式，而不必先接到业务页面上。
 * 运行：pnpm dev 后访问 http://127.0.0.1:5173/preview.html
 * 说明：Vite 默认只以 index.html 为构建入口，本页不会进 dist。
 */
import { createApp, h } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'

import '../src/styles/tokens.css'
import '../src/styles/element-overrides.css'
import '../src/styles/base.css'

import StateEmpty from '../src/components/states/StateEmpty.vue'
import StateError from '../src/components/states/StateError.vue'
import StateForbidden from '../src/components/states/StateForbidden.vue'
import SkeletonList from '../src/components/states/SkeletonList.vue'
import SkeletonTable from '../src/components/states/SkeletonTable.vue'
import SkeletonDetail from '../src/components/states/SkeletonDetail.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/:pathMatch(.*)*', component: { render: () => h('div') } }]
})

const Section = (title, children) => h('section', { class: 'pv-section' }, [
  h('h2', { class: 'pv-title' }, title),
  ...children
])

const App = {
  render() {
    return h('div', { class: 'pv-root' }, [
      Section('空状态（场景化：说清为什么空 + 给主行动）', [
        h(StateEmpty, {
          title: '这个书标下暂时没有卡片',
          hint: '换个分类，或者成为第一个在这儿发布的人',
          actionText: '去发布闲置',
          actionTo: '/product/publish'
        })
      ]),
      Section('错误状态（说明 + 重试；文案由调用方传入，不改写既有提示）', [
        h(StateError, {
          title: '商品不存在或已被删除',
          detail: '网络不稳定时也可能出现，重试一次看看',
          retryText: '重新加载'
        })
      ]),
      Section('未登录 / 无权限', [
        h(StateForbidden, { mode: 'login' }),
        h(StateForbidden, { mode: 'forbidden', message: '无权查看或操作该订单' })
      ]),
      Section('列表骨架（卡片形态，3 列）', [h(SkeletonList, { count: 3, columns: 3 })]),
      Section('列表骨架（行形态，用于会话/订单等单行列表）', [
        h(SkeletonList, { variant: 'row', count: 3, columns: 1 })
      ]),
      Section('表格骨架（后台列表页）', [h(SkeletonTable, { rows: 4, columns: 5 })]),
      Section('详情骨架', [h(SkeletonDetail)])
    ])
  }
}

createApp(App).use(router).mount('#preview')
