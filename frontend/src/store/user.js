import { defineStore } from 'pinia'
import { getToken, setToken, removeToken, getStoredUser, setStoredUser, removeStoredUser } from '@/utils/auth'
import { login as loginApi, logout as logoutApi } from '@/api/common'

/**
 * 用户状态（Pinia）
 * 脚手架阶段：Token 与用户信息的存取逻辑已就绪，登录接口待后端实现。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    userInfo: getStoredUser() || null
  }),

  getters: {
    isLogin: (state) => !!state.token,
    nickname: (state) => state.userInfo?.nickname || '未登录',
    isAdmin: (state) => state.userInfo?.role === 1
  },

  actions: {
    /** 保存登录态 */
    setLoginState(token, userInfo) {
      this.token = token
      this.userInfo = userInfo
      setToken(token)
      setStoredUser(userInfo)
    },

    /** 调用后端登录接口（后端 v0.04 提供后即可直接使用） */
    async login(loginForm) {
      const res = await loginApi(loginForm)
      const { token, userInfo } = res.data || {}
      this.setLoginState(token, userInfo)
      return res
    },

    /** 清空登录态 */
    clearLoginState() {
      this.token = ''
      this.userInfo = null
      removeToken()
      removeStoredUser()
    },

    /** 退出登录 */
    async logout() {
      try {
        if (this.token) {
          await logoutApi()
        }
      } catch (e) {
        // 后端未实现或已过期时忽略，前端状态照常清理
      } finally {
        this.clearLoginState()
      }
    }
  }
})
