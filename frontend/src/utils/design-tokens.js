/**
 * 设计令牌的运行时读取（给 ECharts 等 canvas 场景用）
 *
 * 为什么需要它：ECharts 画在 canvas 上，**不能解析 CSS 变量**，
 * 所以图表的颜色必须在运行时把令牌读成具体色值，而不是写死十六进制。
 * 这样"后台图表配色"也随设计令牌走，改令牌即改图表，不会再出现
 * "前台纸墨世界 + 后台另有一套颜色"的分裂。
 *
 * 用法：
 *   import { cssVar, chartPalette } from '@/utils/design-tokens'
 *   itemStyle: { color: chartPalette.ink() }
 */

/** 读取 CSS 自定义属性的计算值（令牌里可能是 var() 间接引用，getComputedStyle 会解析到底） */
export function cssVar(name, fallback = '') {
  if (typeof window === 'undefined' || !document?.documentElement) return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

/** 八条分类书标色（与 tokens.css 的 --ct-cat-1..8-bar 一一对应） */
export function categoryColors() {
  return [1, 2, 3, 4, 5, 6, 7, 8].map((i) => cssVar(`--ct-cat-${i}-bar`, '#5a5f58'))
}

/** 常用语义色；每个都给出与令牌一致的兜底色，保证令牌缺失时图表仍可读 */
export const chartPalette = {
  ink: () => cssVar('--ct-text-primary', '#1a1d19'),
  muted: () => cssVar('--ct-text-muted', '#5c6159'),
  border: () => cssVar('--ct-border', '#c9ccc4'),
  surface: () => cssVar('--ct-bg-surface', '#ffffff'),
  success: () => cssVar('--el-color-success', '#2f5d3a'),
  warning: () => cssVar('--el-color-warning', '#7a5a2e'),
  danger: () => cssVar('--el-color-danger', '#8c3b32'),
  trading: () => cssVar('--ct-status-trading', '#7a5a2e'),
  sold: () => cssVar('--ct-status-sold', '#8a8f86'),
  category: categoryColors
}
