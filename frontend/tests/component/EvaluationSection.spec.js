/**
 * 商品评价区组件测试（v0.18 / 第6批-㉒）
 *
 * 这个组件是"双向评价"在用户侧的入口，出错的代价是**看不见口碑**：
 * 汇总算错会误导决策，三态缺失会让空数据看起来像加载失败。
 * 所以断言集中在四件事：
 *   ① 有数据时汇总与列表正确渲染（平均分 / 好评率 / 条数）；
 *   ② 无数据时给"还没有评价"的空态，而不是空白；
 *   ③ 加载失败时给可重试的错误态（点重试能恢复）；
 *   ④ 星级分布条按最高档归一化（否则条长全贴地，用户看不出差异）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import EvaluationSection from '@/components/evaluation/EvaluationSection.vue'

vi.mock('@/api/evaluation', () => ({
  getProductEvaluationSummary: vi.fn(),
  getProductEvaluations: vi.fn()
}))
import {
  getProductEvaluationSummary as mockSummary,
  getProductEvaluations as mockList
} from '@/api/evaluation'

import { SkeletonListStub, StateEmptyStub, StateErrorStub } from './setup.js'

const summary = {
  count: 3,
  avgRating: 4.7,
  goodRate: 66.7,
  distribution: { 5: 2, 4: 0, 3: 0, 2: 0, 1: 1 }
}

const records = [
  {
    id: 1,
    fromNickname: '学长',
    fromAvatar: '/upload/a.png',
    roleLabel: '买家评卖家',
    rating: 5,
    content: '书很新',
    tags: '描述相符,态度很好',
    tagList: ['描述相符', '态度很好'],
    createTime: '2026-10-19 10:00:00'
  },
  {
    id: 2,
    fromNickname: '匿名同学',
    fromAvatar: null,
    roleLabel: '卖家评买家',
    rating: 1,
    content: null,
    tags: null,
    tagList: [],
    createTime: '2026-10-19 11:00:00'
  }
]

function mountSection() {
  return mount(EvaluationSection, {
    props: { productId: 8, size: 5 },
    global: {
      stubs: {
        SkeletonList: SkeletonListStub,
        StateEmpty: StateEmptyStub,
        StateError: StateErrorStub
      }
    }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('EvaluationSection：渲染与三态', () => {
  it('有数据时渲染平均分、好评率与总条数', async () => {
    mockSummary.mockResolvedValue({ data: summary })
    mockList.mockResolvedValue({ data: { records, total: 3 } })

    const wrapper = mountSection()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('4.7')
    expect(text).toContain('好评率 66.7%')
    expect(text).toContain('3 条')
  })

  it('渲染评价内容与标签（标签用 · 连接）', async () => {
    mockSummary.mockResolvedValue({ data: summary })
    mockList.mockResolvedValue({ data: { records, total: 3 } })

    const wrapper = mountSection()
    await flushPromises()

    expect(wrapper.text()).toContain('描述相符 · 态度很好')
    expect(wrapper.text()).toContain('书很新')
  })

  it('无头像时用昵称首字兜底，避免裂图', async () => {
    mockSummary.mockResolvedValue({ data: summary })
    mockList.mockResolvedValue({ data: { records, total: 3 } })

    const wrapper = mountSection()
    await flushPromises()

    // 第二条记录 fromAvatar 为 null → 应渲染 .avatar-fallback
    expect(wrapper.findAll('.avatar').length).toBe(2)
    expect(wrapper.findAll('.avatar-fallback').length).toBe(1)
  })

  it('无评价时显示空态，且不渲染列表', async () => {
    mockSummary.mockResolvedValue({ data: { count: 0, avgRating: 0, goodRate: 0, distribution: {} } })
    mockList.mockResolvedValue({ data: { records: [], total: 0 } })

    const wrapper = mountSection()
    await flushPromises()

    expect(wrapper.find('.stub-empty').exists()).toBe(true)
    expect(wrapper.find('.eval-list').exists()).toBe(false)
  })

  it('加载失败时显示错误态；调用 reload 后能恢复', async () => {
    mockSummary.mockRejectedValueOnce(new Error('boom'))
    mockList.mockRejectedValueOnce(new Error('boom'))

    const wrapper = mountSection()
    await flushPromises()
    expect(wrapper.find('.stub-error').exists()).toBe(true)

    // 第二次请求成功 → 点重试（组件通过 defineExpose 暴露 reload）
    mockSummary.mockResolvedValue({ data: summary })
    mockList.mockResolvedValue({ data: { records, total: 3 } })
    await wrapper.vm.reload()
    await flushPromises()

    expect(wrapper.find('.stub-error').exists()).toBe(false)
    expect(wrapper.text()).toContain('4.7')
  })

  it('★ 星级分布条按最高档归一化：5 星最多时是 100%，1 星 1 条是 50%', async () => {
    mockSummary.mockResolvedValue({ data: summary })
    mockList.mockResolvedValue({ data: { records, total: 3 } })

    const wrapper = mountSection()
    await flushPromises()

    const fills = wrapper.findAll('.dist-fill')
    // 顺序是 5,4,3,2,1
    expect(fills[0].attributes('style')).toContain('width: 100%')
    expect(fills[4].attributes('style')).toContain('width: 50%')
  })
})
