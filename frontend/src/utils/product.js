/**
 * 商品图片工具（v0.05）
 *
 * 1. resolveImageUrl：把后端返回的图片地址转成 <img src> 可直接用的地址
 *    - /upload/xxx       后端上传目录（开发环境由 vite 代理转发到 8080）
 *    - /demo-images/xxx  前端 public 下的占位图（vite 直接提供）
 *    - http(s)://xxx     完整地址，原样返回
 * 2. demoImage：没有真实图片时，按商品分类返回一张统一的占位图
 */

/** 分类名 -> 占位图（一级分类与二级分类都覆盖） */
const DEMO_IMAGE_MAP = {
  // 一级分类
  教材书籍: 'textbook.png',
  数码电子: 'digital.png',
  生活用品: 'daily.png',
  运动户外: 'sport.png',
  服饰鞋包: 'clothes.png',
  美妆护肤: 'beauty.png',
  乐器文具: 'default.png',
  其他闲置: 'default.png',
  // 二级分类（更精确）
  公共课教材: 'textbook.png',
  专业课教材: 'textbook.png',
  考研资料: 'textbook.png',
  课外读物: 'textbook.png',
  手机: 'digital.png',
  平板电脑: 'digital.png',
  耳机音响: 'digital.png',
  相机摄影: 'digital.png',
  笔记本电脑: 'computer.png',
  键盘鼠标: 'computer.png',
  宿舍家具: 'dorm.png',
  行李收纳: 'dorm.png',
  日常洗护: 'daily.png',
  自行车: 'sport.png',
  球类器材: 'sport.png',
  健身器材: 'sport.png',
  男装: 'clothes.png',
  女装: 'clothes.png',
  鞋靴: 'clothes.png',
  护肤: 'beauty.png',
  彩妆: 'beauty.png',
  吉他: 'default.png',
  文具用品: 'default.png',
  其他: 'default.png'
}

/**
 * 取某个分类的占位图地址
 * @param {string} categoryName 分类名称
 * @returns {string} 形如 /demo-images/textbook.png
 */
export function demoImage(categoryName) {
  const file = DEMO_IMAGE_MAP[categoryName] || 'default.png'
  return `/demo-images/${file}`
}

/**
 * 商品封面地址：优先用真实图片，没有则用分类占位图
 * @param {{coverImage?: string, categoryName?: string}} product
 */
export function resolveProductImage(product) {
  if (product && product.coverImage) {
    return resolveImageUrl(product.coverImage)
  }
  return demoImage(product && product.categoryName)
}

/** 把后端返回的图片地址转成可访问地址 */
export function resolveImageUrl(url) {
  if (!url) {
    return demoImage()
  }
  // 已经是完整地址或根路径，直接使用
  if (/^(https?:)?\/\//.test(url) || url.startsWith('/')) {
    return url
  }
  return `/${url}`
}

/** 商品详情页的图片列表：product_image 为空时回退到封面/占位图 */
export function resolveDetailImages(product) {
  const images = (product && product.images) || []
  if (images.length > 0) {
    return images.map(resolveImageUrl)
  }
  return [resolveProductImage(product)]
}

/** 成色文案 */
export const CONDITION_OPTIONS = [
  { value: 1, label: '全新' },
  { value: 2, label: '几乎全新' },
  { value: 3, label: '轻微使用痕迹' },
  { value: 4, label: '明显使用痕迹' }
]

/** 商品状态文案与标签颜色 */
export const PRODUCT_STATUS = {
  0: { label: '待审核', type: 'warning' },
  1: { label: '在售', type: 'success' },
  2: { label: '审核不通过', type: 'danger' },
  3: { label: '已下架', type: 'info' },
  4: { label: '交易中', type: 'warning' },
  5: { label: '已售出', type: 'info' }
}

/** 成色文案 */
export function conditionLabel(value) {
  const item = CONDITION_OPTIONS.find((i) => i.value === value)
  return item ? item.label : '未知'
}

/** 金额格式化：15 -> ￥15.00 */
export function formatPrice(price) {
  if (price === null || price === undefined || price === '') {
    return '面议'
  }
  return `￥${Number(price).toFixed(2)}`
}
