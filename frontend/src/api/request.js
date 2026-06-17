import axios from 'axios'

axios.defaults.baseURL = import.meta.env.VITE_API_URL || ''
axios.defaults.timeout = 15000
axios.defaults.withCredentials = true

axios.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }

    if (config.url && !config.url.startsWith('/')) {
      config.url = '/' + config.url
    }

    return config
  },
  error => Promise.reject(error)
)

export default axios
