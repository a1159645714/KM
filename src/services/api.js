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

// API閺堝秴濮熼柊宥囩枂
// 娴兼ê鍘涙担璺ㄦ暏閻滎垰顣ㄩ崣姗€鍣烘稉顓犳畱闁板秶鐤嗛敍灞筋洤閺嬫粍鐥呴張澶婂灟閺嶈宓侀悳顖氼暔閼奉亜濮╅崚銈嗘焽
// 瀵偓閸欐垹骞嗘晶鍐у▏閻?http://localhost:8080/api
// 閻㈢喍楠囬悳顖氼暔娴ｈ法鏁?/api (闁俺绻僋ginx閸欏秴鎮滄禒锝囨倞)
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
 * 闁氨鏁ら惃鍑橮I鐠囬攱鐪伴崙鑺ユ殶
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
 * 鐠併倛鐦堿PI閺堝秴濮? */
export const authApi = {
  /**
   * 缁狅紕鎮婇崨妯兼瑜?   */
  async loginAdmin(username, password, totpCode) {
    return await apiRequest('/auth/admin/login', {
      method: 'POST',
      body: JSON.stringify({ username, password, totpCode })
    });
  },

  /**
   * 閻劍鍩涢惂璇茬秿
   */
  async loginUser(username, password) {
    return await apiRequest('/auth/user/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    });
  },

  /**
   * 閸欐垿鈧線鍋栫粻閬嶇崣鐠囦胶鐖?   */
  async sendEmailCode(email, type = 'register') {
    return await apiRequest('/auth/email-code', {
      method: 'POST',
      body: JSON.stringify({ email, type })
    });
  },

  /**
   * 閻劍鍩涘▔銊ュ斀
   */
  async register(data) {
    return await apiRequest('/auth/register', {
      method: 'POST',
      body: JSON.stringify(data)
    });
  },

  /**
   * 缂佹垵鐣惧▔銊ュ斀
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
   * 閼惧嘲褰囪ぐ鎾冲閻劍鍩涙穱鈩冧紖
   */
  async getUserInfo() {
    return await apiRequest('/auth/user/info');
  },

  /**
   * 閼惧嘲褰嘥OTP闁板秶鐤嗘穱鈩冧紖
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
 * 鐠佸墽鐤咥PI閺堝秴濮? */
export const settingsApi = {
  async getSettings() {
    return await apiRequest('/settings/all');
  },

  async getAllSettings() {
    return await this.getSettings()
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
 * 缂佸瓨濮PI閺堝秴濮? */
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
 * 婢跺洣鍞PI閺堝秴濮? */
export const backupApi = {
  async createBackup() {
    return await apiRequest('/backup/create', {
      method: 'POST'
    });
  }
};

/**
 * 缁崵绮洪惄鎴炲付API閺堝秴濮? */
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
 * 缂佺喕顓窤PI閺堝秴濮? */
export const statsApi = {
  async getStats() {
    return await apiRequest('/stats');
  },

  async getDashboardStats() {
    return await apiRequest('/stats/dashboard')
  },

  async getCardUsageTrends(days = 7) {
    return await apiRequest(`/cards/trend?days=${days}`)
  },

  async getUserActivityStats(days = 7) {
    return await apiRequest(`/stats/user-activity?days=${days}`)
  }
};

/**
 * 閸︺劎鍤庨悽銊﹀煕API閺堝秴濮? */
export const onlineApi = {
  async getOnlineUserList() {
    return await apiRequest('/online/list');
  }
};

/**
 * 閻劍鍩涚粻锛勬倞API閺堝秴濮? */
export const userApi = {
  async getUsers(page = 1, size = 10, keyword = '') {
    const queryParams = new URLSearchParams({
      page: String(page),
      size: String(size)
    })
    if (keyword) {
      queryParams.set('keyword', keyword)
    }
    return await apiRequest(`/admin/users?${queryParams.toString()}`)
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
  },

  async updateUserPassword(id, newPassword) {
    return await apiRequest(`/admin/users/${id}/password`, {
      method: 'PUT',
      body: JSON.stringify({ newPassword })
    });
  },

  async getUserById(id) {
    return await apiRequest(`/admin/users/${id}`);
  }
};

/**
 * 閸椻€崇槕API閺堝秴濮? */
export const cardApi = {
  async generateCards(data) {
    return await apiRequest('/cards/admin/generate', {
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
    return await apiRequest('/cards/admin/all')
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
    return await apiRequest(`/cards/admin/${id}`, {
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
      throw new Error('鐎电厧鍤径杈Е');
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
    return await apiRequest(`/cards/apikey/${apiKeyId}`)
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

  async publicMachineBindQuery(key) {
    return await apiRequest('/machine-bind/query', {
      method: 'POST',
      body: JSON.stringify({ key })
    })
  },

  async publicMachineUnbind(key) {
    return await apiRequest('/machine-bind/unbind', {
      method: 'POST',
      body: JSON.stringify({ key })
    })
  }
};

/**
 * API鐎靛棝鎸滈張宥呭
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
 * 鐠併垹宕烝PI閺堝秴濮? */
export const pricingApi = {
  async getAllPricing() {
    return await apiRequest('/pricing')
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
    return await apiRequest('/orders/create', {
      method: 'POST',
      body: JSON.stringify(orderData)
    });
  },

  async getOrders() {
    return await apiRequest('/orders');
  },

  async getAllOrders(params = {}) {
    const queryParams = new URLSearchParams()
    Object.entries(params || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        queryParams.set(key, String(value))
      }
    })
    return await apiRequest(`/orders/admin/all${queryParams.toString() ? `?${queryParams.toString()}` : ''}`)
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
 * 閺€顖欑帛API閺堝秴濮? */
export const paymentApi = {
  async createPayment(orderNo, paymentType) {
    return await apiRequest('/payment/create', {
      method: 'POST',
      body: JSON.stringify({ orderNo, paymentType })
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
    return await apiRequest(`/oauth/callback/${type}?code=${encodeURIComponent(code)}`);
  }
};

/**
 * 閻劍鍩涙稉顏冩眽鐠у嫭鏋PI閺堝秴濮? */
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


