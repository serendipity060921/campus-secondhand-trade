/**
 * 发布商品表单校验规则（纯函数，单一来源）
 *
 * 对应需求：US-GOODS-01 发布商品
 *   AC2 标题、价格或分类为空 → 前端提示，不发送请求
 *   AC3 价格填写非数字或负数 → 被校验拦截
 *
 * 开发方式：TDD（先写测试 → 红 → 实现 → 绿 → 重构）
 *   测试文件：tests/unit/product-rules.test.mjs（先于本文件编写）
 *
 * ★ 重要约定：本文件的阈值与提示文案**对齐 `views/product/Publish.vue` 的既有行为**
 *   （既有页面是权威）。例如：
 *     · 标题 2~100 字、描述不超过 2000 字、售价最多 8 位整数 2 位小数；
 *     · 图片**不是必填**（不传图时由前端按分类显示占位图），仅限制最多 9 张。
 *   后续页面应直接引用本模块，避免同一套规则在两处各写一遍。
 */

export const PRODUCT_RULES = {
  TITLE_MIN: 2,
  TITLE_MAX: 100,
  /** 售价：最多 8 位整数、2 位小数 */
  PRICE_MAX_INT_DIGITS: 8,
  PRICE_MAX_DECIMALS: 2,
  PRICE_PATTERN: /^\d{1,8}(\.\d{1,2})?$/,
  DESC_MAX: 2000,
  IMAGE_MAX: 9
}

/** 提示文案集中管理，页面与测试使用同一份，避免文案漂移 */
export const MESSAGES = {
  titleRequired: '请输入商品名称',
  titleLength: `商品名称长度为 ${PRODUCT_RULES.TITLE_MIN}~${PRODUCT_RULES.TITLE_MAX} 个字符`,
  priceRequired: '请输入售价',
  pricePositive: '售价必须大于 0',
  priceFormat: `售价最多 ${PRODUCT_RULES.PRICE_MAX_INT_DIGITS} 位整数、${PRODUCT_RULES.PRICE_MAX_DECIMALS} 位小数`,
  categoryRequired: '请选择商品分类',
  descTooLong: `商品描述不能超过 ${PRODUCT_RULES.DESC_MAX} 个字符`,
  imageTooMany: `最多上传 ${PRODUCT_RULES.IMAGE_MAX} 张图片`
}

/** 校验字段的提示顺序：一次只提示第一个错误，顺序稳定 */
const FIELD_ORDER = ['title', 'price', 'categoryId', 'description', 'images']

/**
 * 价格归一化：'12.345' → 12.35（四舍五入到两位小数）；非法值 → null
 * 用途：提交前把用户输入（含粘贴的脏数据）标准化，再交给校验。
 * 说明：用 Number.EPSILON 补偿二进制浮点误差（12.345 * 100 = 1234.4999…）。
 */
export function normalizePrice(raw) {
  if (raw === null || raw === undefined) return null
  const text = String(raw).trim()
  if (!text) return null
  const value = Number(text)
  if (!Number.isFinite(value) || value <= 0) return null
  const cents = Math.round((value + Number.EPSILON) * 100)
  return cents / 100
}

/**
 * 校验发布商品表单
 * @returns {{ ok: boolean, errors: Record<string,string>, firstError: string|null }}
 *          firstError 为**可直接展示给用户的文案**；逐字段标红请用 errors。
 */
export function validateProductForm(form = {}) {
  const errors = {}

  // 1) 标题：必填，去首尾空格后 2 ~ 100 个字符
  const title = String(form.title ?? '').trim()
  if (!title) {
    errors.title = MESSAGES.titleRequired
  } else if (title.length < PRODUCT_RULES.TITLE_MIN || title.length > PRODUCT_RULES.TITLE_MAX) {
    errors.title = MESSAGES.titleLength
  }

  // 2) 售价：必填、大于 0、最多 8 位整数与 2 位小数
  const priceText = form.price === null || form.price === undefined ? '' : String(form.price).trim()
  if (!priceText) {
    errors.price = MESSAGES.priceRequired
  } else if (!Number.isFinite(Number(priceText)) || Number(priceText) <= 0) {
    errors.price = MESSAGES.pricePositive
  } else if (!PRODUCT_RULES.PRICE_PATTERN.test(priceText)) {
    errors.price = MESSAGES.priceFormat
  }

  // 3) 分类：必须选择（id 需为正数）
  const categoryId = form.categoryId
  const categoryNum = Number(categoryId)
  if (categoryId === null || categoryId === undefined || categoryId === '' ||
      !Number.isFinite(categoryNum) || categoryNum <= 0) {
    errors.categoryId = MESSAGES.categoryRequired
  }

  // 4) 描述：选填，不超过 2000 个字符
  const description = String(form.description ?? '').trim()
  if (description.length > PRODUCT_RULES.DESC_MAX) {
    errors.description = MESSAGES.descTooLong
  }

  // 5) 图片：选填（不传则由前端显示占位图），最多 9 张
  const images = Array.isArray(form.images) ? form.images : []
  if (images.length > PRODUCT_RULES.IMAGE_MAX) {
    errors.images = MESSAGES.imageTooMany
  }

  const firstKey = FIELD_ORDER.find((key) => errors[key]) ?? null
  const firstError = firstKey ? errors[firstKey] : null
  return { ok: Object.keys(errors).length === 0, errors, firstError }
}
