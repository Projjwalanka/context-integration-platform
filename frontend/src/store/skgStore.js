import { create } from 'zustand'
import {
  fetchSystemGraph, fetchGraphNode, searchGraphNodes, fetchGraphStats,
  refreshCode, refreshDocs, refreshJira, refreshAllSources, fetchRefreshStatus,
  analyzeImpact, buildContext, deleteGraphNode,
} from '../api/graph'
import toast from 'react-hot-toast'

const useSkgStore = create((set, get) => ({
  // ── Graph data ──────────────────────────────────────────────────────────────
  graphData: null,          // { nodes, edges, nodeCountByType, totalNodes, totalEdges }
  graphLoading: false,
  graphFilter: { nodeType: null, layer: null },

  // ── Selected node ───────────────────────────────────────────────────────────
  selectedNode: null,
  nodeDetail: null,         // { node, neighbors, edges }
  nodeDetailLoading: false,

  // ── Search ──────────────────────────────────────────────────────────────────
  searchResults: [],
  searchLoading: false,

  // ── Stats ───────────────────────────────────────────────────────────────────
  stats: null,
  statsLoading: false,

  // ── Refresh status ──────────────────────────────────────────────────────────
  refreshStatus: [],
  refreshLoading: { CODE: false, DOCS: false, JIRA: false },

  // ── Impact analysis ─────────────────────────────────────────────────────────
  impactResult: null,
  impactLoading: false,

  // ── Context / Explainability ─────────────────────────────────────────────────
  contextResult: null,
  contextLoading: false,
  contextQuery: '',

  // ─────────────────────────────────────────────────────────────────────────────
  // Actions
  // ─────────────────────────────────────────────────────────────────────────────

  fetchGraph: async (filter = {}) => {
    set({ graphLoading: true, graphFilter: filter })
    try {
      const data = await fetchSystemGraph(filter)
      set({ graphData: data, graphLoading: false })
    } catch (e) {
      const detail = e?.response?.data?.error || e?.response?.data?.message
        || e?.response?.statusText || e?.message || 'unknown error'
      const status = e?.response?.status
      console.error('fetchSystemGraph failed', { status, detail, error: e })
      toast.error(`Failed to load system graph${status ? ` (${status})` : ''}: ${detail}`)
      set({ graphLoading: false })
    }
  },

  fetchStats: async () => {
    set({ statsLoading: true })
    try {
      const data = await fetchGraphStats()
      set({ stats: data, statsLoading: false })
    } catch {
      set({ statsLoading: false })
    }
  },

  selectNode: async (node) => {
    set({ selectedNode: node, nodeDetailLoading: true, impactResult: null })
    try {
      const detail = await fetchGraphNode(node.id, 2)
      set({ nodeDetail: detail, nodeDetailLoading: false })
    } catch {
      set({ nodeDetailLoading: false })
    }
  },

  clearSelectedNode: () => set({ selectedNode: null, nodeDetail: null, impactResult: null }),

  searchNodes: async (q, nodeType) => {
    set({ searchLoading: true })
    try {
      const results = await searchGraphNodes(q, nodeType)
      set({ searchResults: results, searchLoading: false })
    } catch {
      set({ searchResults: [], searchLoading: false })
    }
  },

  deleteNode: async (nodeId) => {
    try {
      await deleteGraphNode(nodeId)
      toast.success('Node removed from graph')
      set(s => ({
        selectedNode: null,
        nodeDetail: null,
        graphData: s.graphData ? {
          ...s.graphData,
          nodes: s.graphData.nodes.filter(n => n.id !== nodeId),
          edges: s.graphData.edges.filter(e => e.sourceId !== nodeId && e.targetId !== nodeId),
        } : null,
      }))
    } catch {
      toast.error('Failed to delete node')
    }
  },

  // ── Refresh ─────────────────────────────────────────────────────────────────

  triggerRefresh: async (type) => {
    set(s => ({ refreshLoading: { ...s.refreshLoading, [type]: true } }))
    try {
      if (type === 'CODE')  await refreshCode()
      if (type === 'DOCS')  await refreshDocs()
      if (type === 'JIRA')  await refreshJira()
      toast.success(`${type} ingestion started`)
      setTimeout(() => get().fetchRefreshStatus(), 2000)
    } catch (e) {
      toast.error(e?.response?.data?.error || `Failed to start ${type} ingestion`)
    } finally {
      set(s => ({ refreshLoading: { ...s.refreshLoading, [type]: false } }))
    }
  },

  refreshAll: async () => {
    set(s => ({ refreshLoading: { CODE: true, DOCS: true, JIRA: true } }))
    try {
      const resp = await refreshAllSources()
      toast.success(resp?.message || 'Knowledge graph refresh started')
      const poll = setInterval(async () => {
        await get().fetchRefreshStatus()
        const status = useSkgStore.getState().refreshStatus
        const stillRunning = status.some(s => s.status === 'RUNNING')
        if (!stillRunning) {
          clearInterval(poll)
          set({ refreshLoading: { CODE: false, DOCS: false, JIRA: false } })
          get().fetchGraph()
          get().fetchStats()
        }
      }, 2500)
      setTimeout(() => clearInterval(poll), 180000)
    } catch (e) {
      const detail = e?.response?.data?.error || e?.response?.data?.message || e?.message || 'unknown'
      toast.error(detail, { duration: 8000 })
      set({ refreshLoading: { CODE: false, DOCS: false, JIRA: false } })
    }
  },

  fetchRefreshStatus: async () => {
    try {
      const status = await fetchRefreshStatus()
      set({ refreshStatus: status })
    } catch { /* silent */ }
  },

  // ── Impact analysis ─────────────────────────────────────────────────────────

  runImpactAnalysis: async (nodeId) => {
    set({ impactLoading: true, impactResult: null })
    try {
      const result = await analyzeImpact(nodeId)
      set({ impactResult: result, impactLoading: false })
    } catch {
      toast.error('Impact analysis failed')
      set({ impactLoading: false })
    }
  },

  // ── Context engineering ──────────────────────────────────────────────────────

  buildContext: async (query) => {
    set({ contextLoading: true, contextQuery: query, contextResult: null })
    try {
      const result = await buildContext(query)
      set({ contextResult: result, contextLoading: false })
    } catch {
      toast.error('Context build failed')
      set({ contextLoading: false })
    }
  },

  clearContext: () => set({ contextResult: null, contextQuery: '' }),
}))

export default useSkgStore
