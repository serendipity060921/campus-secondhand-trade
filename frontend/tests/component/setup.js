/**
 * 组件测试的公共 setup（v0.18 / 第6批-㉒）
 *
 * 这里只做一件"会重复 20 遍"的事：把 Element Plus 的组件换成轻量 stub。
 * 原因：真实渲染 el-dialog / el-table 会带来大量与本次断言无关的 DOM，
 * 既慢又容易因为组件库内部改动而误红；本套测试关心的是**组件自身的行为**
 * （传了什么参数、渲染了什么文案、状态切换对不对）。
 *
 * 注意 el-dialog 的 stub 必须渲染 default 与 footer 两个插槽，
 * 否则弹窗里的按钮根本不在 DOM 里，点不到。
 */
import { config } from '@vue/test-utils'

/** 渲染默认插槽的通用 stub（用于 el-form / el-form-item / el-checkbox-group 等） */
export const SlotStub = {
  template: '<div><slot /></div>'
}

/** 弹窗 stub：同时渲染默认插槽与页脚插槽 */
export const DialogStub = {
  props: ['modelValue', 'title'],
  emits: ['update:modelValue'],
  template: '<div class="el-dialog-stub"><slot /><slot name="footer" /></div>'
}

/** 骨架/状态组件的占位 stub（断言它们"是否出现"就够了） */
export const SkeletonListStub = { template: '<div class="stub-skeleton" />' }
export const StateEmptyStub = { props: ['title', 'detail'], template: '<div class="stub-empty">{{ title }}</div>' }
export const StateErrorStub = {
  props: ['title', 'detail', 'retryText'],
  emits: ['retry'],
  template: '<div class="stub-error">{{ title }}</div>'
}

config.global.stubs = {
  'el-dialog': DialogStub,
  'el-form': SlotStub,
  'el-form-item': SlotStub,
  'el-checkbox-group': SlotStub,
  'el-checkbox': SlotStub,
  'el-button': { template: '<button><slot /></button>' },
  'el-tag': { props: ['type', 'size', 'effect'], template: '<span class="el-tag-stub"><slot /></span>' },
  'el-rate': { props: ['modelValue', 'disabled', 'size'], template: '<span class="el-rate-stub" />' },
  'el-switch': { props: ['modelValue'], template: '<span class="el-switch-stub" />' },
  'el-input': { props: ['modelValue'], template: '<textarea class="el-input-stub" />' },
  'el-icon': { template: '<i><slot /></i>' }
}
