import { create } from 'zustand'
import client from '../api/client'

const useKgStore = create((set, get) => ({
  // Knowledge Graph stats
  stats: null,
  statsLoading: false,

  // Entities
  entities: [],
  entitiesLoading: false,
  entityFilter: { type: '', search: '', connectorId: '' },

  // CDC sync states
  cdcStates: [],
  cdcLoading: false,

  // Selected entity for detail/neighbour view
  selectedEntity: null,
  neighbours: null,
  neighboursLoading: false,

  // ── Actions ─────────────────────────────────────────────────────────────

  fetchStats: async () => {
    set({ statsLoading: true })
    try {
      const { data } = await client.get('/kg/stats')
      set({ stats: data })
    } catch { /* silent */ }
    finally { set({ statsLoading: false }) }
  },

  fetchEntities: async (filter = {}) => {
    set({ entitiesLoading: true, entityFilter: { ...get().entityFilter, ...filter } })
    try {
      const params = new URLSearchParams()
      const merged = { ...get().entityFilter, ...filter }
      if (merged.type)        params.set('type', merged.type)
      if (merged.search)      params.set('search', merged.search)
      if (merged.connectorId) params.set('connectorId', merged.connectorId)
      const { data } = await client.get(`/kg/entities?${params}`)
      set({ entities: data })
    } catch { /* silent */ }
    finally { set({ entitiesLoading: false }) }
  },

  selectEntity: async (entity) => {
    set({ selectedEntity: entity, neighbours: null, neighboursLoading: true })
    try {
      const { data } = await client.get(`/kg/entities/${entity.id}/neighbors?hops=1`)
      set({ neighbours: data })
    } catch { /* silent */ }
    finally { set({ neighboursLoading: false }) }
  },

  clearSelectedEntity: () => set({ selectedEntity: null, neighbours: null }),

  fetchCdcStates: async () => {
    set({ cdcLoading: true })
    try {
      const { data } = await client.get('/kg/cdc/states')
      set({ cdcStates: data })
    } catch { /* silent */ }
    finally { set({ cdcLoading: false }) }
  },

  deleteEntity: async (id) => {
    try {
      await client.delete(`/kg/entities/${id}`)
      set(s => ({ entities: s.entities.filter(e => e.id !== id), selectedEntity: null }))
      get().fetchStats()
    } catch { /* silent */ }
  },

  clearAllData: async () => {
    await client.delete('/kg/clear-all')
    set({ entities: [], stats: null, selectedEntity: null, neighbours: null })
  },
}))

export default useKgStore
