/**
 * 发布商品表单校验规则的单元验证
 *
 * ★ TDD 实践：本测试文件先于实现编写（先红后绿）。
 *   首轮实现后测试暴露出一处**真实不一致**：我最初假定的阈值（标题 60 字、描述 500 字、
 *   价格上限 999999.99、图片必填）与 views/product/Publish.vue 的**既有规则**不符。
 *   既有页面行为是权威 —— 因此把规则统一为页面的实际取值：
 *     标题 2~100 字、描述 ≤ 2000 字、售价最多 8 位整数 2 位小数、图片**选填**（最多 9 张）。
 *
 * 对应需求：US-GOODS-01 发布商品（验收标准 AC2 空值拦截、AC3 非法价格拦截）
 * 运行：node tests/unit/product-rules.test.mjs
 */
import {
  validateProductForm,
  normalizePrice,
  PRODUCT_RULES,
  MESSAGES
} from '../../frontend/src/utils/productRules.js'

let pass = 0
let fail = 0

function eq(actual, expected, label) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  if (ok) {
    pass++
    console.log(`  ✓ ${label}`)
  } else {
    fail++
    console.log(`  ✗ ${label}\n      期望 ${JSON.stringify(expected)}\n      实际 ${JSON.stringify(actual)}`)
  }
}

/** 生成一份合法表单，便于逐个字段破坏 */
function validForm(overrides = {}) {
  return {
    title: '高等数学教材（上册）',
    price: '25.5',
    categoryId: 3,
    description: '大一用过，有少量笔记',
    images: ['/upload/demo.png'],
    ...overrides
  }
}

// ─────────────────────────────────────────
console.log('\n一、标题校验（US-GOODS-01 AC2：必填与长度 2~100）')
eq(validateProductForm(validForm()).ok, true, '合法表单整体通过')
eq(validateProductForm(validForm({ title: '' })).errors.title, MESSAGES.titleRequired, '标题为空 → 报错')
eq(validateProductForm(validForm({ title: '   ' })).errors.title, MESSAGES.titleRequired, '标题只有空白字符 → 视为空')
eq(validateProductForm(validForm({ title: 'a' })).errors.title, MESSAGES.titleLength, '标题过短 → 报错')
eq(validateProductForm(validForm({ title: 'x'.repeat(PRODUCT_RULES.TITLE_MAX) })).ok, true, '标题正好 100 字 → 通过（边界）')
eq(validateProductForm(validForm({ title: 'x'.repeat(PRODUCT_RULES.TITLE_MAX + 1) })).errors.title,
   MESSAGES.titleLength, '标题 101 字 → 报错（边界）')
eq(validateProductForm(validForm({ title: '  二手自行车  ' })).ok, true, '标题首尾空格不影响通过')

console.log('\n二、售价校验（US-GOODS-01 AC3：必须大于 0 且格式合法）')
eq(validateProductForm(validForm({ price: '' })).errors.price, MESSAGES.priceRequired, '售价为空 → 报错')
eq(validateProductForm(validForm({ price: 0 })).errors.price, MESSAGES.pricePositive, '售价为 0 → 报错')
eq(validateProductForm(validForm({ price: -5 })).errors.price, MESSAGES.pricePositive, '售价为负 → 报错')
eq(validateProductForm(validForm({ price: 'abc' })).errors.price, MESSAGES.pricePositive, '售价非数字 → 报错')
eq(validateProductForm(validForm({ price: '25.555' })).errors.price, MESSAGES.priceFormat, '售价 3 位小数 → 报错')
eq(validateProductForm(validForm({ price: '123456789' })).errors.price, MESSAGES.priceFormat, '售价 9 位整数 → 报错')
eq(validateProductForm(validForm({ price: '25.5' })).ok, true, '字符串型售价按数字处理（JSON 类型宽容）')
eq(validateProductForm(validForm({ price: 0.01 })).ok, true, '最小合法售价 0.01 → 通过')
eq(validateProductForm(validForm({ price: '99999999.99' })).ok, true, '最大合法售价（8 位整数 2 位小数）→ 通过')

console.log('\n三、分类校验（必须选择分类）')
eq(validateProductForm(validForm({ categoryId: null })).errors.categoryId, MESSAGES.categoryRequired, '未选分类 → 报错')
eq(validateProductForm(validForm({ categoryId: 0 })).errors.categoryId, MESSAGES.categoryRequired, '分类 id 为 0 → 视为未选')
eq(validateProductForm(validForm({ categoryId: 7 })).ok, true, '合法分类 id 通过')

console.log('\n四、描述与图片（描述选填 ≤2000；图片选填 ≤9 张 —— 与页面既有行为一致）')
eq(validateProductForm(validForm({ description: '' })).ok, true, '描述选填，留空可通过')
eq(validateProductForm(validForm({ description: 'x'.repeat(PRODUCT_RULES.DESC_MAX) })).ok, true, '描述正好 2000 字 → 通过（边界）')
eq(validateProductForm(validForm({ description: 'x'.repeat(PRODUCT_RULES.DESC_MAX + 1) })).errors.description,
   MESSAGES.descTooLong, '描述 2001 字 → 报错')
eq(validateProductForm(validForm({ images: [] })).ok, true, '不传图片也能发布（前端按分类显示占位图）')
eq(validateProductForm(validForm({ images: new Array(PRODUCT_RULES.IMAGE_MAX).fill('/a.png') })).ok, true,
   '正好 9 张图片 → 通过（边界）')
eq(validateProductForm(validForm({ images: new Array(PRODUCT_RULES.IMAGE_MAX + 1).fill('/a.png') })).errors.images,
   MESSAGES.imageTooMany, '10 张图片 → 报错')

console.log('\n五、汇总行为（一次只提示第一个错误，顺序稳定）')
const empty = validateProductForm({ title: '', price: '', categoryId: null, images: [] })
eq(empty.ok, false, '空表单不通过')
eq(empty.firstError, MESSAGES.titleRequired, 'firstError 按「标题 → 售价 → 分类 → 描述 → 图片」顺序取第一个')
eq(Object.keys(empty.errors).sort(), ['categoryId', 'price', 'title'], '所有错误一次性返回（便于表单逐项标红）')
eq(validateProductForm(validForm({ title: '', price: 'abc' })).firstError, MESSAGES.titleRequired,
   '同时有多个错误时，仍取顺序最靠前的那个')
eq(validateProductForm(validForm()).firstError, null, '全部合法时 firstError 为 null')

console.log('\n六、normalizePrice（价格归一化，供提交前使用）')
eq(normalizePrice('12.345'), 12.35, '超过两位小数 → 四舍五入到两位')
eq(normalizePrice(' 25.5 '), 25.5, '去除首尾空格')
eq(normalizePrice('abc'), null, '非数字 → null')
eq(normalizePrice(''), null, '空字符串 → null')
eq(normalizePrice(0), null, '0 → null（不合法）')
eq(normalizePrice(null), null, 'null → null')
eq(normalizePrice('1e3'), 1000, '科学计数法可被 Number 正确解析')

console.log('\n七、规则常量与文案（单一来源，防止规则被悄悄改小）')
eq(PRODUCT_RULES.TITLE_MIN, 2, '标题最少 2 字')
eq(PRODUCT_RULES.TITLE_MAX, 100, '标题最多 100 字（与页面一致）')
eq(PRODUCT_RULES.DESC_MAX, 2000, '描述最多 2000 字（与页面一致）')
eq(PRODUCT_RULES.IMAGE_MAX, 9, '图片最多 9 张（与页面一致）')
eq(PRODUCT_RULES.PRICE_PATTERN.test('99999999.99'), true, '价格正则接受 8 位整数 + 2 位小数')
eq(PRODUCT_RULES.PRICE_PATTERN.test('100000000'), false, '价格正则拒绝 9 位整数')
eq(MESSAGES.titleRequired, '请输入商品名称', '标题必填文案与页面一致')
eq(MESSAGES.pricePositive, '售价必须大于 0', '售价正数文案与页面一致')

console.log(`\n结果：通过 ${pass} 条，失败 ${fail} 条`)
if (fail > 0) {
  console.log('✗ 单元验证未通过')
  process.exit(1)
}
console.log('✓ 单元验证通过')
