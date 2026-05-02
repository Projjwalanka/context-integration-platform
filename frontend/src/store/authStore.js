import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import client from '../api/client'

export const useAuthStore = create(
  persist(
    (set, get) => ({
      // DEV: pre-seeded so UI components that read user/token don't break
      token: 'dev-bypass-token',
      refreshToken: 'dev-bypass-refresh',
      user: { id: 'dev', email: 'admin@bank.com', name: 'Admin User', roles: ['ROLE_ADMIN', 'ROLE_USER'] },

      login: async (email, password) => {
        const { data } = await client.post('/auth/login', { email, password })
        localStorage.setItem('auth_token', data.accessToken)
        set({ token: data.accessToken, refreshToken: data.refreshToken,
              user: { id: data.userId, email: data.email, name: data.fullName, roles: data.roles } })
        return data
      },

      register: async (email, password, firstName, lastName) => {
        const { data } = await client.post('/auth/register', { email, password, firstName, lastName })
        localStorage.setItem('auth_token', data.accessToken)
        set({ token: data.accessToken, refreshToken: data.refreshToken,
              user: { id: data.userId, email: data.email, name: data.fullName, roles: data.roles } })
        return data
      },

      logout: async () => {
        try { await client.post('/auth/logout') } catch (_) {}
        localStorage.removeItem('auth_token')
        set({ token: null, refreshToken: null, user: null })
      },
    }),
    { name: 'auth-store', partialize: s => ({ token: s.token, refreshToken: s.refreshToken, user: s.user }) }
  )
)
