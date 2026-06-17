import { defineStore } from 'pinia'
import { ref } from 'vue'
import request from '../api/request'
import { ElMessage } from 'element-plus'
import router from '../router'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const username = ref(localStorage.getItem('username') || '')
  const isAuthenticated = ref(!!localStorage.getItem('token'))

  // 初始化时设置axios请求头
  const initializeAuthHeader = () => {
    const savedToken = localStorage.getItem('token')
    if (savedToken) {
      request.defaults.headers.common['Authorization'] = `Bearer ${savedToken}`
      isAuthenticated.value = true
    }
  }

  // 添加axios响应拦截器处理认证错误
  request.interceptors.response.use(
    response => response,
    error => {
      console.error('请求错误:', error.response?.status, error.message)
      if (error.response && [401, 403].includes(error.response.status)) {
        // 如果收到认证/授权错误，清除令牌并提示用户
        ElMessage.warning('登录已过期或无权限访问，请重新登录')
        logout()
        router.push('/login')
      }
      return Promise.reject(error)
    }
  )

  // 调用初始化方法
  initializeAuthHeader()

  const login = async (loginForm) => {
    try {
      console.log('发送登录请求...')
      const response = await request.post('/api/auth/login', loginForm)
      
      if (!response.data || response.data.code !== 200) {
        throw new Error(response.data?.message || '登录失败')
      }
      
      const responseData = response.data.data
      if (!responseData || !responseData.token) {
        throw new Error('登录失败，未获取到有效的令牌')
      }
      
      const newToken = responseData.token
      const newUsername = responseData.username || loginForm.username
      
      token.value = newToken
      username.value = newUsername
      isAuthenticated.value = true
      
      localStorage.setItem('token', newToken)
      localStorage.setItem('username', newUsername)
      
      // 设置axios请求头
      request.defaults.headers.common['Authorization'] = `Bearer ${newToken}`
      
      console.log('登录成功，令牌已设置')
      return {
        token: newToken,
        username: newUsername
      }
    } catch (error) {
      console.error('登录错误:', error)
      if (error.response) {
        const status = error.response.status
        const message = error.response.data?.message || '未知错误'
        
        if (status === 401) {
          throw new Error('用户名或密码错误')
        } else if (status === 429) {
          throw new Error('登录尝试次数过多，请稍后再试')
        } else {
          throw new Error(`登录失败: ${message}`)
        }
      }
      throw error
    }
  }

  const logout = async () => {
    request.post('/api/auth/logout').catch(() => {})
    token.value = ''
    username.value = ''
    isAuthenticated.value = false
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    delete request.defaults.headers.common['Authorization']
    console.log('已登出，令牌已清除')
  }

  // 检查令牌是否有效的方法
  const checkAuth = async () => {
    if (!token.value) {
      isAuthenticated.value = false
      return false
    }
    
    try {
      // 可以添加一个简单的API调用来验证令牌
      await request.get('/api/auth/check')
      isAuthenticated.value = true
      return true
    } catch (error) {
      if (error.response && error.response.status === 401) {
        logout()
      }
      isAuthenticated.value = false
      return false
    }
  }

  return {
    token,
    username,
    isAuthenticated,
    login,
    logout,
    initializeAuthHeader,
    checkAuth
  }
}) 
