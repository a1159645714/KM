import { ElMessage, ElMessageBox } from 'element-plus'

const AUTH_EXPIRED_EVENT = 'xxgkami:auth-expired'
let authExpiredHandled = false

function clearAuthStorage() {
  localStorage.removeItem('token')
  localStorage.removeItem('refreshToken')
  localStorage.removeItem('user')
  localStorage.removeItem('userInfo')
  localStorage.removeItem('isLoggedIn')
}

function notifyAuthExpired(message = 'Current login expired, please sign in again') {
  if (authExpiredHandled) return
  authExpiredHandled = true

  const storedUserInfo = localStorage.getItem('userInfo')
  let isAdmin = false
  if (storedUserInfo) {
    try {
      const parsed = JSON.parse(storedUserInfo)
      isAdmin = parsed?.role === 'admin'
    } catch (e) {
      // Ignore invalid stored data.
    }
  }

  clearAuthStorage()
  window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT, {
    detail: { isAdmin, message }
  }))

  ElMessage.closeAll()
  ElMessageBox.alert(message, 'Login Expired', {
    confirmButtonText: 'OK',
    type: 'warning',
    showClose: false,
    callback: () => {
      authExpiredHandled = false
      if (isAdmin) {
        window.location.href = '/#/admin'
      } else {
        window.location.href = '/'
      }
    }
  })
}

// API 基础配置
// 开发环境默认通过 Vite 代理到 http://localhost:8080/api
// 生产环境使用相对路径 /api（由 Nginx 反向代理到后端）
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api';
let isRefreshing = false;
let failedQueue = [];

const processQueue = (error, token = null) => {
  failedQueue.forEach(prom => {
    if (error) {
      prom.reject(error);
    } else {
      prom.resolve(token);
    }
  });
  failedQueue = [];
};

/**
 * 通用 API 请求封装（带 401 自动刷新重试）
 */
async function apiRequest(endpoint, options = {}) {
  const url = `${API_BASE_URL}${endpoint}`;

  // Get token from storage
  const token = localStorage.getItem('token');

  const defaultOptions = {
    method: 'GET',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { 'Authorization': `Bearer ${token}` } : {})
    },
  };

  const config = { ...defaultOptions, ...options };
  // Merge headers carefully
  config.headers = { ...defaultOptions.headers, ...options.headers };

  // If body is FormData, let the browser set the Content-Type header
  if (options.body instanceof FormData) {
    delete config.headers['Content-Type'];
  }

  try {
    const response = await fetch(url, config);

    if (response.status === 401) {
       if (endpoint.includes('/login') || endpoint.includes('/refresh')) {
            throw new Error('Authentication failed');
        }

        if (isRefreshing) {
          return new Promise((resolve, reject) => {
            failedQueue.push({ resolve, reject });
          }).then(newToken => {
            config.headers['Authorization'] = `Bearer ${newToken}`;
            return fetch(url, config).then(res => res.json());
          });
        }

        isRefreshing = true;
        const refreshToken = localStorage.getItem('refreshToken');

        if (!refreshToken) {
            isRefreshing = false;
            notifyAuthExpired();
            throw new Error('No refresh token available');
        }

        try {
            const refreshResponse = await fetch(`${API_BASE_URL}/auth/refresh`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ refreshToken })
            });

            const refreshData = await refreshResponse.json();

            if (refreshData.success) {
                const newToken = refreshData.data.token;
                localStorage.setItem('token', newToken);
                if (refreshData.data.refreshToken) {
                    localStorage.setItem('refreshToken', refreshData.data.refreshToken);
                }

                processQueue(null, newToken);
                isRefreshing = false;

                // Retry original request
                config.headers['Authorization'] = `Bearer ${newToken}`;
                const retryResponse = await fetch(url, config);
                return await retryResponse.json();
            } else {
                throw new Error('Refresh failed');
            }
        } catch (refreshError) {
            processQueue(refreshError, null);
            isRefreshing = false;
            notifyAuthExpired();
            throw refreshError;
        }
    }

    if (!response.ok) {
      let errorMsg = `HTTP error! status: ${response.status}`;
      try {
        const errorText = await response.text();
        if (errorText) {
          try {
             const errorJson = JSON.parse(errorText);
             if (errorJson.message) errorMsg = errorJson.message;
             else errorMsg = errorText;
          } catch (e) {
             errorMsg = errorText;
          }
        }
      } catch (e) {
        // ignore
      }
      throw new Error(errorMsg);
    }

    // Check content type before parsing JSON
    const contentType = response.headers.get("content-type");
    if (contentType && contentType.includes("application/json")) {
      return await response.json();
    } else {
      // For non-JSON responses (like simple strings), return text
      return await response.text();
    }
  } catch (error) {
    console.error('API request failed:', error);
    throw error;
  }
}

/**
 * 认证 API 封装
 */
export const authApi = {
  /**
   * 管理员登录
   */
  async loginAdmin(username, password, totpCode) {
    return await apiRequest('/auth/admin/login', {
      method: 'POST',
      body: JSON.stringify({ username, password, totpCode })
    });
  },

  /**
   * 用户登录
   */
  async loginUser(username, password) {
    return await apiRequest('/auth/user/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    });
  },

  /**
   * 发送邮箱验证码
   */
  async sendEmailCode(email, type = 'register') {
    return await apiRequest('/auth/email-code', {
      method: 'POST',
      body: JSON.stringify({ email, type })
    });
  },

  /**
   * 用户注册
   */
  async register(data) {
    return await apiRequest('/auth/register', {
      method: 'POST',
      body: JSON.stringify(data)
    });
  },

  /**
   * 第三方登录后注册绑定
   */
  async registerBind(data) {
    return await apiRequest('/auth/register-bind', {
      method: 'POST',
      body: JSON.stringify(data)
    });
  },

  async logout(id, role) {
    return await apiRequest('/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ id, role })
    });
  },

  async updateAdmin(data) {
    return await apiRequest('/auth/admin/update', {
      method: 'POST',
      body: JSON.stringify(data)
    });
  },

  /**
   * 获取当前登录用户信息
   */
  async getUserInfo() {
    return await apiRequest('/auth/user/info');
  },

  /**
   * 设置 TOTP 二次验证
   */
  async setupTotp(id) {
    return await apiRequest('/auth/totp/setup', {
      method: 'POST',
      body: JSON.stringify({ id })
    });
  },

  async enableTotp(idOrData, secret, code) {
    const payload = typeof idOrData === 'object'
      ? idOrData
      : { id: idOrData, secret, code }
    return await apiRequest('/auth/totp/enable', {
      method: 'POST',
      body: JSON.stringify(payload)
    });
  },

  async disableTotp(id) {
    return await apiRequest('/auth/totp/disable', {
      method: 'POST',
      body: JSON.stringify({ id })
    });
  },

  async sendResetPasswordCode(username, email) {
    return await apiRequest('/auth/reset-code', {
      method: 'POST',
      body: JSON.stringify({ username, email })
    });
  },

  async sendResetCode(username, email) {
    return await this.sendResetPasswordCode(username, email)
  },

  async resetPassword(data) {
    return await apiRequest('/auth/reset-password', {
      method: 'POST',
      body: JSON.stringify(data)
    });
  },

  async sendRecoveryCode(username) {
    return await apiRequest('/auth/totp/recovery-code', {
      method: 'POST',
      body: JSON.stringify({ username })
    });
  },

  async disableTotpByRecoveryCode(username, recoveryCode) {
    return await apiRequest('/auth/totp/disable-by-recovery', {
      method: 'POST',
      body: JSON.stringify({ username, code: recoveryCode })
    });
  },

  async disableTotpByRecovery(username, recoveryCode) {
    return await this.disableTotpByRecoveryCode(username, recoveryCode)
  },

  async getBindToken() {
    return await apiRequest('/auth/bind/token');
  },

  async validateBindToken(userId, token) {
    return await apiRequest('/auth/bind/validate', {
      method: 'POST',
      body: JSON.stringify({ userId, token })
    });
  },
};

/**
 * 系统设置 API 封装
 */
export const settingsApi = {
  async getSettings() {
    return await apiRequest('/settings/all');
  },

  async getAllSettings() {
    const response = await this.getSettings()
    return response?.data ? response : { success: true, data: response }
  },

  async getPublicSettings() {
    return await apiRequest('/settings/public');
  },

  async saveSettings(settings) {
    return await apiRequest('/settings/save', {
      method: 'POST',
      body: JSON.stringify(settings)
    });
  },

  async testEmail(config) {
    return await apiRequest('/settings/email/test', {
      method: 'POST',
      body: JSON.stringify(config)
    });
  },

  async sendTestEmail(to, config = {}) {
    return await this.testEmail({ to, ...config })
  }
};

/**
 * 维护模式 API 封装
 */
export const maintenanceApi = {
  async getStatus() {
    return await apiRequest('/maintenance/status');
  },

  async updateSettings(settings) {
    return await apiRequest('/maintenance/update', {
      method: 'POST',
      body: JSON.stringify(settings)
    });
  },

  async clearCache() {
    return await apiRequest('/maintenance/clear-cache', {
      method: 'POST'
    });
  },

  async clearLogs() {
    return await apiRequest('/maintenance/clear-logs', {
      method: 'POST'
    });
  },

  async createBackup() {
    return await backupApi.createBackup()
  }
};

/**
 * 数据库备份 API 封装
 */
export const backupApi = {
  async createBackup() {
    return await apiRequest('/backup/create', {
      method: 'POST'
    });
  }
};

/**
 * 系统监控 API 封装
 */
export const monitorApi = {
  async getSystemMetrics() {
    return await apiRequest('/monitor/system');
  },

  async getJvmMetrics() {
    return await apiRequest('/monitor/all');
  },

  async getDiskMetrics() {
    return await apiRequest('/monitor/database');
  },

  async getNetworkMetrics() {
    return await apiRequest('/monitor/api');
  },

  async getProcessList() {
    return await apiRequest('/monitor/users');
  },

  async getAllMonitorData() {
    return await apiRequest('/monitor/all');
  },

  async getSystemStatus() {
    return await apiRequest('/monitor/system')
  },

  async getDatabaseStatus() {
    return await apiRequest('/monitor/database')
  },

  async getApiStatus() {
    return await apiRequest('/monitor/api')
  },

  async checkUpdate() {
    return await apiRequest('/monitor/check-update');
  }
};

/**
 * 统计 API 封装
 */
export const statsApi = {
  async getDashboardStats() {
    const response = await apiRequest('/stats/dashboard')
    return response?.data || response || {}
  },

  async getCardUsageTrends(days = 7) {
    return await apiRequest(`/cards/trend?days=${days}`)
  },

  async getUserActivityStats(days = 7) {
    return await apiRequest(`/stats/user-activity?days=${days}`)
  }
};

/**
 * 在线用户 API 封装
 */
export const onlineApi = {
  async getOnlineUserList() {
    return await apiRequest('/online/list');
  }
};

/**
 * 用户管理 API 封装
 */
export const userApi = {
  async getUsers(page = 1, size = 10, keyword = '') {
    const queryParams = new URLSearchParams({
      page: String(page),
      size: String(size)
    })
    if (keyword) {
      queryParams.set('keyword', keyword)
    }
    const response = await apiRequest(`/admin/users?${queryParams.toString()}`)
    return {
      ...response,
      users: response?.users || response?.data?.users || [],
      total: response?.total || response?.data?.total || 0
    }
  },

  async updateUserStatus(id, status) {
    return await apiRequest(`/admin/users/${id}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status })
    })
  },

  async createUser(user) {
    return await apiRequest('/admin/users', {
      method: 'POST',
      body: JSON.stringify(user)
    });
  },

  async updateUser(id, user) {
    return await apiRequest(`/admin/users/${id}`, {
      method: 'PUT',
      body: JSON.stringify(user)
    });
  },

  async deleteUser(id) {
    return await apiRequest(`/admin/users/${id}`, {
      method: 'DELETE'
    });
  }
};

/**
 * 卡密 API 封装
 */
export const cardApi = {
  async generateCards(data) {
    return await apiRequest('/cards/admin/create', {
      method: 'POST',
      body: JSON.stringify(data)
    });
  },

  async createCards(data) {
    return await this.generateCards(data)
  },

  async getCards(params = {}) {
    const queryParams = new URLSearchParams(params).toString();
    return await apiRequest(`/cards/admin/list${queryParams ? `?${queryParams}` : ''}`);
  },

  async getAllCards() {
    const response = await apiRequest('/cards/admin/all')
    return response?.data ? response : { success: true, data: response }
  },

  async getUserCards(userId) {
    return await apiRequest(`/cards/user/${userId}`);
  },

  async updateCard(id, data) {
    return await apiRequest(`/cards/admin/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    });
  },

  async deleteCard(id) {
    return await apiRequest(`/cards/${id}`, {
      method: 'DELETE'
    });
  },

  async updateCardStatus(id, status) {
    return await apiRequest(`/cards/admin/${id}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status })
    });
  },

  async updateAdminStatus(id, status) {
    return await this.updateCardStatus(id, status)
  },

  async searchCards(params = {}) {
    const queryParams = new URLSearchParams(params).toString();
    return await apiRequest(`/cards/admin/search${queryParams ? `?${queryParams}` : ''}`);
  },

  async verifyCard(cardKey, machineCode, apiKey) {
    return await apiRequest('/cards/verify', {
      method: 'POST',
      headers: apiKey ? { 'X-API-Key': apiKey } : {},
      body: JSON.stringify({ cardKey, machineCode })
    });
  },

  async getUsageTrend(days = 30) {
    return await apiRequest(`/cards/trend?days=${days}`);
  },

  async exportCards(params = {}) {
    const queryParams = new URLSearchParams(params).toString();
    const url = `${API_BASE_URL}/cards/admin/export${queryParams ? `?${queryParams}` : ''}`;
    const token = localStorage.getItem('token');
    const response = await fetch(url, {
      headers: token ? { 'Authorization': `Bearer ${token}` } : {}
    });
    if (!response.ok) {
      throw new Error('导出卡密失败');
    }
    return await response.blob();
  },

  async batchDelete(ids) {
    return await apiRequest('/cards/admin/batch-delete', {
      method: 'POST',
      body: JSON.stringify({ ids })
    });
  },

  async batchUpdateStatus(ids, status) {
    return await apiRequest('/cards/admin/batch-status', {
      method: 'POST',
      body: JSON.stringify({ ids, status })
    });
  },

  async batchExport(ids) {
    return await apiRequest('/cards/admin/batch-export', {
      method: 'POST',
      body: JSON.stringify({ ids })
    });
  },

  async getCardDetail(id) {
    return await apiRequest(`/cards/admin/${id}`);
  },

  async findByMachineCode(machineCode) {
    return await apiRequest(`/cards/admin/machine/${encodeURIComponent(machineCode)}`);
  },

  async selfUnbind(cardKey, machineCode) {
    return await apiRequest('/cards/unbind', {
      method: 'POST',
      body: JSON.stringify({ cardKey, machineCode })
    });
  },

  async getApiKeyCards(apiKeyId) {
    const response = await apiRequest(`/cards/apikey/${apiKeyId}`)
    return response?.data ? response : { success: true, data: response }
  },

  async useCard(cardKey, machineCode = '', apiKey = '', deviceId = '', ipAddress = '') {
    return await apiRequest('/cards/use', {
      method: 'POST',
      body: JSON.stringify({
        card_key: cardKey,
        machine_code: machineCode,
        api_key: apiKey,
        device_id: deviceId,
        ip_address: ipAddress
      })
    })
  },

  async publicMachineBindQuery(cardKey) {
    return await apiRequest('/public/cards/machine-bind/query', {
      method: 'POST',
      body: JSON.stringify({ card_key: cardKey })
    })
  },

  async publicMachineUnbind(cardKey, machineCode) {
    return await apiRequest('/public/cards/machine-bind/unbind', {
      method: 'POST',
      body: JSON.stringify({ card_key: cardKey, machine_code: machineCode })
    })
  }
};

/**
 * API 密钥管理
 */
export const apiKeyApi = {
  async getApiKeys() {
    return await apiRequest('/admin/apikeys');
  },

  async getAllApiKeys() {
    const response = await this.getApiKeys()
    return response?.data || response || []
  },

  async getAllUsers() {
    const response = await apiRequest('/admin/users?page=1&size=1000')
    return response?.users || response?.data?.users || response?.data || response || []
  },

  async createApiKey(data) {
    return await apiRequest('/admin/apikeys', {
      method: 'POST',
      body: JSON.stringify(data)
    });
  },

  async updateApiKey(id, data) {
    return await apiRequest(`/admin/apikeys/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    });
  },

  async deleteApiKey(id) {
    return await apiRequest(`/admin/apikeys/${id}`, {
      method: 'DELETE'
    });
  },

  async getApiKeyStats(id) {
    return await apiRequest(`/admin/apikeys/${id}/stats`);
  },

  async assignUser(id, userId) {
    return await apiRequest(`/admin/apikeys/${id}/users`, {
      method: 'POST',
      body: JSON.stringify({ userId })
    })
  },

  async unassignUser(id, userId) {
    return await apiRequest(`/admin/apikeys/${id}/users/${userId}`, {
      method: 'DELETE'
    })
  }
};

/**
 * 卡密定价 API 封装
 */
export const pricingApi = {
  async getAllPricing() {
    const response = await apiRequest('/pricing')
    return response?.data ? response : { success: true, data: response }
  },

  async addPricing(data) {
    return await apiRequest('/pricing', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },

  async updatePricing(id, data) {
    return await apiRequest(`/pricing/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  },

  async deletePricing(id) {
    return await apiRequest(`/pricing/${id}`, {
      method: 'DELETE'
    })
  }
};

export const orderApi = {
  async createOrder(orderData) {
    return await apiRequest('/orders', {
      method: 'POST',
      body: JSON.stringify(orderData)
    });
  },

  async getOrders() {
    const response = await apiRequest('/orders')
    return response?.data || response || []
  },

  async getAllOrders(params = {}) {
    const queryParams = new URLSearchParams()
    Object.entries(params || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        queryParams.set(key, String(value))
      }
    })
    const response = await apiRequest(`/orders/admin/all${queryParams.toString() ? `?${queryParams.toString()}` : ''}`)
    return response?.data ? response : { success: true, data: response }
  },

  async searchOrders(params = {}) {
    return await this.getAllOrders(params)
  },

  async updateOrderStatus(orderNo, status) {
    return await apiRequest('/orders/admin/updateStatus', {
      method: 'POST',
      body: JSON.stringify({ orderNo, status })
    })
  }
};

/**
 * 支付 API 封装
 */
export const paymentApi = {
  async createPayment(orderNo, paymentMethod) {
    return await apiRequest('/payment/pay', {
      method: 'POST',
      body: JSON.stringify({ orderNo, paymentMethod })
    });
  }
};

/**
 * OAuth API閺堝秴濮? */
export const oauthApi = {
  async getLoginUrl(type) {
    return await apiRequest(`/oauth/login/${type}`);
  },

  async handleCallback(type, code) {
    return await apiRequest(`/oauth/callback?type=${encodeURIComponent(type)}&code=${encodeURIComponent(code)}`);
  }
};

/**
 * 用户资料 API 封装
 */
export const userProfileApi = {
  async getProfile() {
    return await apiRequest('/user/profile');
  },

  async updateProfile(data) {
    return await apiRequest('/user/profile', {
      method: 'PUT',
      body: JSON.stringify(data)
    });
  },

  async changePassword(oldPassword, newPassword) {
    return await apiRequest('/user/password', {
      method: 'POST',
      body: JSON.stringify({ oldPassword, newPassword })
    });
  },

  async getSocialAccounts() {
    return await apiRequest('/user/social');
  },

  async getSocialBindings() {
    return await this.getSocialAccounts()
  },

  async unbindSocial(type) {
    return await apiRequest('/user/social/unbind', {
      method: 'POST',
      body: JSON.stringify({ type })
    });
  },

  async bindSocial(token) {
    return await apiRequest('/user/social/bind', {
      method: 'POST',
      body: JSON.stringify({ token })
    });
  },

  async uploadAvatar(file) {
    const formData = new FormData()
    formData.append('file', file)
    return await apiRequest('/user/avatar', {
      method: 'POST',
      body: formData
    })
  }
};

export { apiRequest, API_BASE_URL, AUTH_EXPIRED_EVENT };


