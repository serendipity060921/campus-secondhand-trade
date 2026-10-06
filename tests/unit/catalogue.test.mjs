/**
 * 索书号式编号的单元验证（无第三方测试框架，node 直接跑，CI 可执行）
 *
 * 运行：node tests/unit/catalogue.test.mjs
 * 说明：项目未引入 vitest；这里用最小断言集覆盖纯函数的正常路径与边界，
 *       让 CI 从"只构建"升级为"构建 + 至少有一处真实断言"。
 */
import {
  buildClassIndex,
  shelfCode,
  classBarVar,
  classTintVar,
  CLASS_CODES,
  UNKNOWN_CODE
} from '../../frontend/src/utils/catalogue.js'

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

// 与数据库真实结构一致的分类树（8 个顶层 + 部分子分类）
const tree = [
  { id: 1, name: '教材书籍', children: [{ id: 9, name: '公共课教材' }, { id: 10, name: '专业课教材' }] },
  { id: 2, name: '数码电子', children: [{ id: 13, name: '手机' }, { id: 14, name: '笔记本电脑' }, { id: 15, name: '平板电脑' }, { id: 16, name: '耳机音响' }] },
  { id: 3, name: '生活用品', children: [{ id: 19, name: '宿舍家具' }] },
  { id: 4, name: '运动户外', children: [{ id: 24, name: '健身器材' }] },
  { id: 5, name: '服饰鞋包', children: [{ id: 25, name: '男装' }] },
  { id: 6, name: '美妆护肤', children: [{ id: 28, name: '护肤' }] },
  { id: 7, name: '乐器文具', children: [{ id: 30, name: '文具用品' }, { id: 29, name: '吉他' }] },
  { id: 8, name: '其他闲置', children: [{ id: 999999, name: '孤儿分类' }] }
]

const { byId, tops } = buildClassIndex(tree)

console.log('一、分类索引')
eq(tops.length, 8, '顶层分类识别出 8 个')
eq(tops.map((t) => t.code), ['G4', 'TN', 'TS', 'G8', 'TS9', 'TQ', 'J6', 'Z9'], '八个分类号与映射表一致')
eq(byId.get(9).code, 'G4', '子分类「公共课教材」继承顶层书标 G4')
eq(byId.get(19).code, 'TS', '子分类「宿舍家具」继承顶层书标 TS')
eq(byId.get(28).index, 6, '子分类「护肤」取得令牌序号 6')

console.log('\n二、索书号生成')
eq(shelfCode({ id: 4, categoryId: 15 }, byId).code, 'TN·004', 'id=4 / 平板电脑 → TN·004')
eq(shelfCode({ id: 12, categoryId: 19 }, byId).code, 'TS·012', 'id=12 / 宿舍家具 → TS·012')
eq(shelfCode({ id: 1, categoryId: 10 }, byId).code, 'G4·001', 'id=1 / 专业课教材 → G4·001')
eq(shelfCode({ id: 6, categoryId: 9 }, byId).code, 'G4·006', '不同子分类共用同一书标（G4）')

console.log('\n三、边界与兜底')
eq(shelfCode({ id: 1234, categoryId: 13 }, byId).code, 'TN·1234', 'id 超过三位时不截断（1234）')
eq(shelfCode({ id: 5, categoryId: 999999 }, byId).code, 'Z9·005', '孤儿分类仍归入「其他闲置」书标')
eq(shelfCode({ id: 5, categoryId: 424242 }, byId).code, `${UNKNOWN_CODE}·005`, '未知分类用 ZZ 明确标注，而不是伪装成已知分类')
eq(shelfCode({ id: null, categoryId: 13 }, byId).code, 'TN·—', 'id 缺失时种次号用占位符，不抛异常')
eq(shelfCode({ id: 7 }, null).code, `${UNKNOWN_CODE}·007`, '未提供索引时安全兜底')
eq(shelfCode({ id: 7, categoryId: 30 }, byId, { pad: 5, separator: '-' }).code, 'J6-00007', '支持自定义位数与分隔符（乐器文具→J6）')

console.log('\n四、颜色只经令牌，不在组件里写死')
eq(classBarVar(2), 'var(--ct-cat-2-bar)', '书标实色走令牌')
eq(classTintVar(8), 'var(--ct-cat-8-tint)', '书标浅色场走令牌')
eq(classBarVar(99), 'var(--ct-cat-8-bar)', '越界序号兜底到第 8 条，不产生无效变量')

console.log('\n五、映射表自检')
eq(Object.keys(CLASS_CODES).length, 8, '分类号映射表恰好 8 条（与数据库顶层分类数一致）')
eq(new Set(Object.values(CLASS_CODES)).size, 8, '八个分类号互不重复')

// 接口实际返回的是**扁平**列表（一级分类 parentId=0），与上面的树形不同。
// 早期实现只认 parent === null，把 33 个节点全当成一级分类，子分类索书号全落到 ZZ；
// 首页色带也因此渲染出 33 段。这里补上该形态的回归覆盖。
console.log('\n六、扁平 parentId 形态（接口真实返回）')
const flat = [
  { id: 1, parentId: 0, name: '教材书籍', sortOrder: 1 },
  { id: 9, parentId: 1, name: '公共课教材', sortOrder: 9 },
  { id: 2, parentId: 0, name: '数码电子', sortOrder: 2 },
  { id: 15, parentId: 2, name: '平板电脑', sortOrder: 15 },
  { id: 8, parentId: 0, name: '其他闲置', sortOrder: 8 },
  { id: 32, parentId: 8, name: '其他', sortOrder: 32 }
]
const flatIndex = buildClassIndex(flat)
eq(flatIndex.tops.length, 3, 'parentId=0 的才是一级分类（3 个），不是全部 6 个')
eq(flatIndex.tops.map((t) => t.name), ['教材书籍', '数码电子', '其他闲置'], '一级分类按 sortOrder 排序')
eq(flatIndex.byId.get(15).code, 'TN', '子分类按 parentId 继承书标（平板电脑→TN）')
eq(shelfCode({ id: 7, categoryId: 15 }, flatIndex.byId).code, 'TN·007', '扁平形态下索书号正确（不再落到 ZZ）')
eq(shelfCode({ id: 7, categoryId: 9 }, flatIndex.byId).code, 'G4·007', '扁平形态下另一分支同样正确')

console.log('\n七、parentId 为 null / 缺省时的兼容')
eq(buildClassIndex([{ id: 1, parentId: null, name: '教材书籍' }, { id: 9, parentId: 1, name: '公共课教材' }]).tops.length,
   1, 'parentId=null 也视为一级分类')
eq(buildClassIndex([{ id: 1, name: '教材书籍' }, { id: 2, name: '数码电子' }]).tops.length,
   2, '完全没有 parentId 时整张列表视为一级分类')
eq(buildClassIndex([]).tops.length, 0, '空列表安全返回 0 个一级分类')

// v0.16：分类接口新增"在售件数"（一级分类含子分类汇总），索引要把该字段透传出去；
// 字段缺失时必须保持 null，不能显示成 0 —— 0 件和"没有这个字段"是两回事。
console.log('\n八、在售件数透传（v0.16 新增字段）')
const withCount = buildClassIndex([
  { id: 1, parentId: 0, name: '教材书籍', productCount: 2 },
  { id: 9, parentId: 1, name: '公共课教材', productCount: 1 },
  { id: 2, parentId: 0, name: '数码电子', productCount: 4 },
  { id: 3, parentId: 0, name: '生活用品', productCount: 0 }
])
eq(withCount.tops.map((t) => t.productCount), [2, 4, 0], '一级分类的件数原样透传（含真实的 0 件）')
eq(buildClassIndex([{ id: 1, parentId: 0, name: '教材书籍' }]).tops[0].productCount, null,
   '接口没给件数时保持 null，不冒充 0 件')
eq(buildClassIndex([{ id: 1, parentId: 0, name: '教材书籍', productCount: '3' }]).tops[0].productCount, 3,
   '字符串型件数也按数字处理（后端 JSON 类型宽容）')

console.log(`\n结果：通过 ${pass} 条，失败 ${fail} 条`)
if (fail > 0) {
  console.log('✗ 单元验证未通过')
  process.exit(1)
}
console.log('✓ 单元验证通过')
