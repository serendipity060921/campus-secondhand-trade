import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

// ---- 设计系统（v0.16 全站改版）----
// 顺序不可调换：
//   1) tokens.css            定义设计令牌（唯一允许出现原始色值的地方）
//   2) element-overrides.css 把 Element Plus 的 CSS 变量接到令牌上（混合策略的关键）
//   3) base.css              重置、排版基线、焦点环、纸面与墨线工具类
import './styles/tokens.css'
import './styles/element-overrides.css'
import './styles/base.css'

import App from './App.vue'
import router from './router'

/**
 * 应用入口
 * 版本：v0.03 里程碑：前后端分离脚手架
 */
const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
