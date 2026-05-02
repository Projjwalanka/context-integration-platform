import { useState, useEffect } from 'react'
import {
  GitBranch, Activity, Network, RefreshCw,
  Layers, Grid3x3, ListTree, X,
} from 'lucide-react'
import { motion, AnimatePresence } from 'framer-motion'
import KnowledgeGraphPanel from '../components/kg/KnowledgeGraphPanel'
import PipelineMonitor from '../components/kg/PipelineMonitor'
import SystemGraphView from '../components/skg/SystemGraphView'
import { LayeredView, MatrixView, TreeView } from '../components/skg/GraphVisualizations'
import useSkgStore from '../store/skgStore'

const NODE_TYPE_COLORS = {
  Service:    '#4C8BF5', Module: '#00BCD4', Component: '#8BC34A',
  Class:      '#9C27B0', Function: '#FF5722', Api: '#FF9800',
  Database:   '#F44336', Table: '#E91E63', Document: '#795548',
  DesignDoc:  '#607D8B', Story: '#009688', Bug: '#F44336',
  Repository_Node: '#3F51B5',
}

// Top-level tabs: only graph + legacy
const TABS = [
  { id: 'skg',     label: 'System Graph',    Icon: Network,   group: 'skg'    },
  { id: 'graph',   label: 'Entity Graph',    Icon: GitBranch, group: 'legacy' },
  { id: 'monitor', label: 'Pipeline Monitor',Icon: Activity,  group: 'legacy' },
]

// Visualization modes within the System Graph tab
const VIEW_MODES = [
  { id: 'force',   label: 'Force Graph',  Icon: Network },
  { id: 'layered', label: 'Layered',      Icon: Layers },
  { id: 'matrix',  label: 'Matrix',       Icon: Grid3x3 },
  { id: 'tree',    label: 'Tree',         Icon: ListTree },
]

// ── Node detail sidebar ─────────────────────────────────────────────────────
function NodeDetailSidebar({ node, detail, loading, onClose }) {
  if (!node) return null
  const nodeColor = NODE_TYPE_COLORS[node.nodeType] || '#6B7280'

  return (
    <motion.div
      initial={{ x: 320, opacity: 0 }}
      animate={{ x: 0, opacity: 1 }}
      exit={{ x: 320, opacity: 0 }}
      className="absolute right-0 top-0 bottom-0 w-80 bg-white border-l border-gray-200 shadow-2xl z-30 flex flex-col overflow-hidden"
    >
      <div className="flex items-start gap-3 p-4 border-b border-gray-100">
        <div className="w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0"
          style={{ backgroundColor: nodeColor + '20' }}>
          <div className="w-3.5 h-3.5 rounded-full" style={{ backgroundColor: nodeColor }} />
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-gray-900 truncate">{node.name}</p>
          <span className="inline-block text-[10px] font-semibold px-2 py-0.5 rounded-full"
            style={{ backgroundColor: nodeColor + '20', color: nodeColor }}>
            {node.nodeType}
          </span>
        </div>
        <button onClick={onClose} className="p-1 rounded text-gray-400 hover:text-gray-600 transition">
          <X className="h-4 w-4" />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {node.description && <p className="text-xs text-gray-600 leading-relaxed">{node.description}</p>}

        <div className="space-y-1.5">
          {node.layer && <InfoRow label="Layer" value={node.layer} />}
          {node.sourceRef && <InfoRow label="Source ref" value={node.sourceRef} />}
          {node.updatedAt && <InfoRow label="Last updated" value={new Date(node.updatedAt).toLocaleDateString()} />}
        </div>

        {loading ? (
          <div className="py-4 text-center">
            <div className="h-4 w-4 border-2 border-indigo-300 border-t-transparent rounded-full animate-spin mx-auto" />
          </div>
        ) : detail?.neighbors?.length > 1 ? (
          <div>
            <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2">
              Connected ({detail.neighbors.length - 1})
            </p>
            <div className="space-y-1.5">
              {detail.neighbors.filter(n => n.id !== node.id).slice(0, 12).map(n => (
                <div key={n.id} className="flex items-center gap-2 p-2 rounded-lg bg-gray-50 border border-gray-100">
                  <div className="w-2 h-2 rounded-full flex-shrink-0"
                    style={{ backgroundColor: NODE_TYPE_COLORS[n.nodeType] || '#6b7280' }} />
                  <span className="text-xs text-gray-700 truncate flex-1">{n.name}</span>
                  <span className="text-[9px] text-gray-400 flex-shrink-0">{n.nodeType}</span>
                </div>
              ))}
            </div>
          </div>
        ) : null}

        {detail?.edges?.length > 0 && (
          <div>
            <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2">
              Relationships ({detail.edges.length})
            </p>
            <div className="space-y-1">
              {detail.edges.slice(0, 10).map((e, i) => {
                const isSource = e.sourceId === node.id
                return (
                  <div key={i} className="text-[10px] flex items-center gap-1 text-gray-500">
                    <span className={isSource ? 'text-gray-400' : 'text-indigo-400'}>{isSource ? '→' : '←'}</span>
                    <span className="px-1 py-0.5 bg-gray-100 rounded font-mono text-[9px]">{e.relType}</span>
                  </div>
                )
              })}
            </div>
          </div>
        )}

        <div className="rounded-xl bg-indigo-50 border border-indigo-100 p-3">
          <p className="text-[10px] font-semibold text-indigo-700 mb-1">Want impact analysis or AI reasoning?</p>
          <p className="text-[10px] text-indigo-600 leading-relaxed">
            Ask in chat: <span className="italic">"What breaks if I change {node.name}?"</span> —
            the assistant uses this graph to answer.
          </p>
        </div>
      </div>
    </motion.div>
  )
}

function InfoRow({ label, value }) {
  return (
    <div className="flex justify-between items-start gap-2 text-xs">
      <span className="text-gray-400 flex-shrink-0">{label}</span>
      <span className="text-gray-700 text-right truncate max-w-[160px]">{value}</span>
    </div>
  )
}

// ── Main Page ───────────────────────────────────────────────────────────────
export default function KnowledgePage() {
  const [activeTab, setActiveTab] = useState('skg')
  const [viewMode, setViewMode]   = useState('force')
  const {
    selectedNode, nodeDetail, nodeDetailLoading,
    clearSelectedNode, fetchStats, fetchGraph, stats,
    refreshAll, refreshLoading,
  } = useSkgStore()

  useEffect(() => {
    fetchStats()
    fetchGraph()
  }, [])

  const isRefreshing = refreshLoading?.CODE || refreshLoading?.DOCS || refreshLoading?.JIRA

  return (
    <div className="flex flex-col h-screen bg-gray-50">
      {/* Top nav */}
      <div className="flex items-center gap-1 px-4 py-2.5 border-b border-gray-100 bg-white flex-shrink-0">
        <div className="flex items-center gap-2 mr-4">
          <div className="w-7 h-7 rounded-xl bg-gradient-to-br from-indigo-600 to-blue-500 flex items-center justify-center">
            <Network className="h-4 w-4 text-white" />
          </div>
          <div>
            <span className="text-sm font-bold text-gray-900">System Knowledge Graph</span>
            <span className="ml-2 text-[10px] text-indigo-600 font-semibold bg-indigo-50 rounded-full px-2 py-0.5">Neo4j</span>
          </div>
        </div>

        {stats && (
          <div className="hidden md:flex items-center gap-3 mr-4 text-xs text-gray-500">
            <span className="font-bold text-gray-800">{stats.totalNodes || 0}</span> nodes
            <span className="w-px h-3 bg-gray-200" />
            <span className="font-bold text-gray-800">{stats.totalEdges || 0}</span> edges
          </div>
        )}

        <div className="flex gap-0.5 flex-wrap">
          <div className="flex gap-0.5 mr-2 border-r border-gray-200 pr-2">
            {TABS.filter(t => t.group === 'skg').map(({ id, label, Icon }) => (
              <button
                key={id}
                onClick={() => setActiveTab(id)}
                className={`flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-medium transition ${
                  activeTab === id ? 'bg-indigo-600 text-white' : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100'
                }`}
              >
                <Icon className="h-3.5 w-3.5" />
                <span className="hidden sm:inline">{label}</span>
              </button>
            ))}
          </div>

          <div className="flex gap-0.5">
            {TABS.filter(t => t.group === 'legacy').map(({ id, label, Icon }) => (
              <button
                key={id}
                onClick={() => setActiveTab(id)}
                className={`flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-medium transition ${
                  activeTab === id ? 'bg-gray-700 text-white' : 'text-gray-400 hover:text-gray-600 hover:bg-gray-100'
                }`}
              >
                <Icon className="h-3.5 w-3.5" />
                <span className="hidden sm:inline">{label}</span>
              </button>
            ))}
          </div>
        </div>

        {/* Refresh KG (uses existing connectors) */}
        {activeTab === 'skg' && (
          <button
            onClick={refreshAll}
            disabled={isRefreshing}
            className="ml-auto flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-medium bg-indigo-50 text-indigo-700 hover:bg-indigo-100 disabled:opacity-60 transition"
            title="Re-ingest from already-configured GitHub / Confluence / Jira connectors"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${isRefreshing ? 'animate-spin' : ''}`} />
            <span>{isRefreshing ? 'Refreshing…' : 'Refresh Knowledge Graph'}</span>
          </button>
        )}
      </div>

      {/* View-mode bar (only on SKG tab) */}
      {activeTab === 'skg' && (
        <div className="flex items-center gap-1 px-4 py-1.5 border-b border-gray-100 bg-white flex-shrink-0">
          <span className="text-[10px] font-semibold text-gray-400 uppercase tracking-wider mr-2">Visualization</span>
          {VIEW_MODES.map(({ id, label, Icon }) => (
            <button
              key={id}
              onClick={() => setViewMode(id)}
              className={`flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-[11px] font-medium transition ${
                viewMode === id ? 'bg-gray-900 text-white' : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100'
              }`}
            >
              <Icon className="h-3 w-3" />
              {label}
            </button>
          ))}
        </div>
      )}

      {/* Content area */}
      <div className="flex-1 overflow-hidden relative">
        {activeTab === 'skg' && (
          <div className="h-full relative">
            {viewMode === 'force'   && <SystemGraphView />}
            {viewMode === 'layered' && <LayeredView />}
            {viewMode === 'matrix'  && <MatrixView />}
            {viewMode === 'tree'    && <TreeView />}
            <AnimatePresence>
              {selectedNode && (
                <NodeDetailSidebar
                  node={selectedNode}
                  detail={nodeDetail}
                  loading={nodeDetailLoading}
                  onClose={clearSelectedNode}
                />
              )}
            </AnimatePresence>
          </div>
        )}

        {activeTab === 'graph'   && <KnowledgeGraphPanel />}
        {activeTab === 'monitor' && (
          <div className="h-full overflow-y-auto">
            <PipelineMonitor />
          </div>
        )}
      </div>
    </div>
  )
}
