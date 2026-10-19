/**
 * 评价弹窗组件测试（v0.18 / 第6批-㉒）
 *
 * 这个弹窗承载"双向评价"的写入，风险点不是外观而是**写错**：
 *   · 前端能不能把评价指向别人？（不能 —— 只传 orderId，被评价人由后端推导）
 *   · 用户已经评过还会不会打开？（打开前先问后端，已评过则直接提示）
 *   · 后端说"已评价过"时，界面是错误红还是友好提示？（应为提示，不是报错）
 * 因此断言集中在提交的**载荷**与三种业务码的**话术**上。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import EvaluationDialog from '@/components/evaluation/EvaluationDialog.vue'

vi.mock('@/api/evaluation', () => ({
  getOrderEvaluations: vi.fn(),
  submitEvaluation: vi.fn(),
  EVALUATION_TAGS: ['描述相符', '态度很好']
}))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

import {
  getOrderEvaluations as mockOrderEvals,
  submitEvaluation as mockSubmit,
  EVALUATION_TAGS
} from '@/api/evaluation'
import { ElMessage } from 'element-plus'

const order = {
  id: 1,
  orderNo: 'CT20261019001',
  productTitle: '九成新教材',
  productImage: '/demo-images/default.png'
}

function mountDialog(props = {}) {
  return mount(EvaluationDialog, { props: { modelValue: true, order, ...props } })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('EvaluationDialog：打开前预检', () => {
  it('★ 已经评过（mine=true）→ 返回 false，父组件不应打开弹窗', async () => {
    mockOrderEvals.mockResolvedValue({ data: [{ mine: true }] })

    const wrapper = mountDialog()
    const ok = await wrapper.vm.checkBeforeOpen()

    expect(ok).toBe(false)
    expect(ElMessage.info).toHaveBeenCalledWith('你已经评价过这笔订单了')
  })

  it('没评过 → 返回 true', async () => {
    mockOrderEvals.mockResolvedValue({ data: [{ mine: false }] })
    const wrapper = mountDialog()
    expect(await wrapper.vm.checkBeforeOpen()).toBe(true)
  })

  it('预检查询失败时不阻塞提交（后端仍会做最终校验）', async () => {
    mockOrderEvals.mockRejectedValue(new Error('network'))
    const wrapper = mountDialog()
    expect(await wrapper.vm.checkBeforeOpen()).toBe(true)
  })
})

describe('EvaluationDialog：提交载荷与话术', () => {
  it('★ 只提交 orderId（不传被评价人），并带上评分/标签/匿名', async () => {
    mockSubmit.mockResolvedValue({ code: 200, message: '评价成功', data: { id: 9 } })

    const wrapper = mountDialog()
    wrapper.vm.form.rating = 4
    wrapper.vm.form.content = '  书很新  '
    wrapper.vm.form.tags = ['描述相符', '态度很好']
    wrapper.vm.form.anonymous = 1

    await wrapper.vm.submit()
    await flushPromises()

    expect(mockSubmit).toHaveBeenCalledTimes(1)
    const payload = mockSubmit.mock.calls[0][0]
    expect(payload).toEqual({
      orderId: 1,
      rating: 4,
      content: '书很新',
      tags: '描述相符,态度很好',
      anonymous: 1
    })
    expect(payload.toUserId).toBeUndefined() // ★ 关键：前端不许指定被评价人
    expect(wrapper.emitted('submitted')).toBeTruthy()
    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
  })

  it('内容与标签留空时提交 undefined，而不是空串', async () => {
    mockSubmit.mockResolvedValue({ code: 200, data: {} })
    const wrapper = mountDialog()
    wrapper.vm.form.content = '   '
    wrapper.vm.form.tags = []

    await wrapper.vm.submit()

    const payload = mockSubmit.mock.calls[0][0]
    expect(payload.content).toBeUndefined()
    expect(payload.tags).toBeUndefined()
  })

  it('★ 后端 9004（已评价过）→ 用友好提示而不是报错，且不触发 submitted', async () => {
    mockSubmit.mockRejectedValue(Object.assign(new Error('已评价'), { code: 9004 }))
    const wrapper = mountDialog()

    await wrapper.vm.submit()
    await flushPromises()

    expect(ElMessage.warning).toHaveBeenCalledWith('你已经评价过这笔订单了')
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(wrapper.emitted('submitted')).toBeFalsy()
  })

  it('9003（订单未完成）与 9002（非买卖双方）各有对应提示', async () => {
    mockSubmit.mockRejectedValueOnce(Object.assign(new Error('x'), { code: 9003 }))
    let wrapper = mountDialog()
    await wrapper.vm.submit()
    expect(ElMessage.warning).toHaveBeenCalledWith('订单完成后才能评价')

    vi.clearAllMocks()
    mockSubmit.mockRejectedValueOnce(Object.assign(new Error('x'), { code: 9002 }))
    wrapper = mountDialog()
    await wrapper.vm.submit()
    expect(ElMessage.warning).toHaveBeenCalledWith('只有买卖双方可以评价该订单')
  })

  it('未知错误 → 走通用错误提示', async () => {
    mockSubmit.mockRejectedValue(Object.assign(new Error('服务器开小差'), { code: 500 }))
    const wrapper = mountDialog()
    await wrapper.vm.submit()
    expect(ElMessage.error).toHaveBeenCalled()
  })

  it('标签候选由 API 模块提供（避免前后端各写一份）', () => {
    expect(EVALUATION_TAGS).toEqual(['描述相符', '态度很好'])
  })
})
