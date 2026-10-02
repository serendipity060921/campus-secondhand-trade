import request from './request'

/**
 * 商品模块接口（v0.05）
 *
 * 说明：request.js 已配置 baseURL = '/api'，并且会自动把 Token 放进请求头，
 * 因此这里不再重复写 /api，也不用处理鉴权。
 */

/** 商品分页列表（只返回上架商品） GET /api/product/list */
export function getProductList(params) {
  return request({ url: '/product/list', method: 'get', params })
}

/** 商品详情 GET /api/product/{id} */
export function getProductDetail(id) {
  return request({ url: `/product/${id}`, method: 'get' })
}

/** 发布商品 POST /api/product/publish（需登录） */
export function publishProduct(data) {
  return request({ url: '/product/publish', method: 'post', data })
}

/** 我的商品 GET /api/product/mine（需登录） */
export function getMyProducts(params) {
  return request({ url: '/product/mine', method: 'get', params })
}

/** 上架 / 下架 PUT /api/product/status（需登录，只能操作自己的商品） */
export function updateProductStatus(data) {
  return request({ url: '/product/status', method: 'put', data })
}

/** 分类列表 GET /api/category/list */
export function getCategoryList(params) {
  return request({ url: '/category/list', method: 'get', params })
}

/**
 * 图片上传 POST /api/product/upload（需登录）
 *
 * 注意：request.js 里默认 Content-Type 是 application/json，
 * 而 axios 遇到 JSON 类型的 Content-Type 时会把 FormData 转成 JSON 字符串，
 * 所以这里必须显式声明 multipart/form-data
 * （浏览器最终会用它自己的、带 boundary 的 multipart 头，axios 会把我们写的这个去掉）。
 *
 * @param {FormData} formData 含 file 或 files 字段
 * @param {Function} onProgress 上传进度回调
 */
export function uploadProductImages(formData, onProgress) {
  return request({
    url: '/product/upload',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: onProgress
  })
}
