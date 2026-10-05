/**
 * 索书号式编号与分类书标（纯函数，无副作用、不依赖接口改动）
 *
 * 设计意图：把商品列表变成"可扫读、可指认"的馆藏目录 ——
 * 每件商品有一个类似索书号的短编号，每个顶层分类有一条书标色。
 *
 * ⚠ 诚实标注（论文与答辩时必须同样表述）：
 *   这是对图书馆索书号**形态的借用**，不是真实的中图法分类。
 *   分类号是按数据库里的顶层分类手工映射的两字母代号，
 *   种次号就是商品 id。它服务于界面可扫读性，不声称具备图书分类学意义。
 *
 * 与接口的关系：不改任何接口。分类树从既有的 getCategories() 取，
 * 用 buildClassIndex() 把「子分类 → 顶层分类 → 书标」索引一次即可。
 */

/** 顶层分类名 → 分类号（两字母代号） */
export const CLASS_CODES = {
  教材书籍: 'G4',
  数码电子: 'TN',
  生活用品: 'TS',
  运动户外: 'G8',
  服饰鞋包: 'TS9',
  美妆护肤: 'TQ',
  乐器文具: 'J6',
  其他闲置: 'Z9'
}

/** 分类号 → 令牌序号（1~8），组件据此取 var(--ct-cat-N-bar / -tint)，避免组件里写裸色值 */
export const CLASS_TOKEN_INDEX = {
  G4: 1,
  TN: 2,
  TS: 3,
  G8: 4,
  TS9: 5,
  TQ: 6,
  J6: 7,
  Z9: 8
}

/** 未识别分类的兜底：归入"其他闲置"的色标，但仍用 ZZ 表明它不是已知分类 */
export const UNKNOWN_CODE = 'ZZ'
const UNKNOWN_INDEX = 8

/**
 * 由分类树建立索引：子分类 id → 顶层分类信息
 * @param {Array} tree getCategories() 返回的树（顶层节点含 children，或扁平列表含 parentId）
 * @returns {{byId: Map<number, {topId:number, topName:string, code:string, index:number}>,
 *            tops: Array<{id:number, name:string, code:string, index:number}>}}
 */
export function buildClassIndex(tree = []) {
  const byId = new Map()
  const tops = []

  // 兼容两种形态：树（children）与扁平（parentId）
  const roots = []
  const flats = []
  const walk = (nodes, parent) => {
    for (const n of nodes || []) {
      if (!n) continue
      flats.push({ ...n, _parent: parent })
      if (parent === null) roots.push(n)
      if (n.children && n.children.length) walk(n.children, n)
    }
  }
  walk(tree, null)

  for (const n of (roots.length ? roots : tree)) {
    const id = Number(n.id)
    const name = String(n.name || '').trim()
    const code = CLASS_CODES[name] || UNKNOWN_CODE
    tops.push({ id, name, code, index: CLASS_TOKEN_INDEX[code] || UNKNOWN_INDEX })
  }

  // 顶层自身
  for (const t of tops) {
    byId.set(t.id, { topId: t.id, topName: t.name, code: t.code, index: t.index })
  }
  // 子分类：继承其父级的书标
  for (const f of flats) {
    if (f._parent === null) continue
    const pid = Number(f._parent.id)
    const parent = byId.get(pid)
    byId.set(Number(f.id), parent
      ? { topId: parent.topId, topName: parent.topName, code: parent.code, index: parent.index }
      : { topId: pid, topName: String(f._parent.name || ''), code: UNKNOWN_CODE, index: UNKNOWN_INDEX })
  }

  return { byId, tops }
}

/**
 * 生成索书号式编号
 * @param {object} product 含 id 与 categoryId 的商品对象
 * @param {Map} byId buildClassIndex().byId
 * @param {{pad?: number, separator?: string}} [opts]
 * @returns {{code: string, index: number, classCode: string, topName: string}} code 形如 "TN·004"
 */
export function shelfCode(product, byId, opts = {}) {
  const pad = opts.pad ?? 3
  const separator = opts.separator ?? '·'
  const id = Number(product?.id)
  const info = byId && byId.get(Number(product?.categoryId))
  const classCode = info?.code || UNKNOWN_CODE
  const index = info?.index || UNKNOWN_INDEX
  // 注意 Number(null) === 0：必须显式要求正整数，否则 null 会变成 "000" 而不是占位符
  const validId = Number.isInteger(id) && id > 0
  const serial = validId ? String(id).padStart(pad, '0') : '—'
  return {
    code: `${classCode}${separator}${serial}`,
    classCode,
    index,
    topName: info?.topName || ''
  }
}

/** 书标实色（组件用：var(--ct-cat-N-bar)） */
export function classBarVar(index) {
  return `var(--ct-cat-${CLASS_TOKEN_INDEX_BY_INDEX(index)}-bar)`
}

/** 书标浅色场（组件用：var(--ct-cat-N-tint)） */
export function classTintVar(index) {
  return `var(--ct-cat-${CLASS_TOKEN_INDEX_BY_INDEX(index)}-tint)`
}

function CLASS_TOKEN_INDEX_BY_INDEX(index) {
  const n = Number(index)
  return n >= 1 && n <= 8 ? n : UNKNOWN_INDEX
}
