import { useState, useEffect, useCallback } from 'react'
import { useChatStore } from '../../store/chatStore'
import client from '../../api/client'
import { motion } from 'framer-motion'
import { Plus, RefreshCw, Zap, BarChart2, Lock, Clock } from 'lucide-react'
import ConnectorConfigModal from './ConnectorConfigModal'
import DataSourceDetailModal from './DataSourceDetailModal'
import { CONNECTOR_META } from './connectorMeta'
import toast from 'react-hot-toast'
import { formatDistanceToNow } from 'date-fns'

function StatusDot({ connector }) {
  if (connector.lastError)
    return <span className="block h-2.5 w-2.5 rounded-full bg-red-500 flex-shrink-0" title={connector.lastError} />
  if (connector.verified)
    return <span className="block h-2.5 w-2.5 rounded-full bg-green-500 flex-shrink-0" title="Connected" />
  return <span className="block h-2.5 w-2.5 rounded-full bg-gray-300 flex-shrink-0" title="Not verified" />
}

export default function ConnectorPanel() {
  const [connectors, setConnectors] = useState([])
  const [loading, setLoading] = useState(false)
  const [showAddModal, setShowAddModal] = useState(false)
  const [editConnector, setEditConnector] = useState(null)
  const [detailConnector, setDetailConnector] = useState(null)
  const { activeConnectors, toggleConnector } = useChatStore()

  const loadConnectors = useCallback(async () => {
    setLoading(true)
    try {
      const { data } = await client.get('/connectors')
      setConnectors(data)
    } catch {
      toast.error('Failed to load data sources')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadConnectors() }, [loadConnectors])

  return (
    <div className="h-full flex flex-col bg-white">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-gray-800">Data Sources</h3>
          {connectors.some(c => c.verified) && (
            <span className="text-[10px] bg-green-100 text-green-700 rounded-full px-1.5 py-0.5 font-medium leading-none">
              {connectors.filter(c => c.verified).length} live
            </span>
          )}
        </div>
        <div className="flex items-center gap-1">
          <button
            onClick={loadConnectors}
            title="Refresh"
            className="p-1.5 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          </button>
          <button
            onClick={() => { setEditConnector(null); setShowAddModal(true) }}
            className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-2.5 py-1.5 text-xs font-medium text-white hover:bg-blue-700 transition"
          >
            <Plus className="h-3.5 w-3.5" /> Add
          </button>
        </div>
      </div>

      {/* Connector Cards */}
      <div className="flex-1 overflow-y-auto px-3 py-3 space-y-2">
        {!loading && connectors.length === 0 && (
          <div className="flex flex-col items-center justify-center py-12 text-center">
            <div className="w-12 h-12 rounded-2xl bg-gray-100 flex items-center justify-center mb-3">
              <Zap className="h-6 w-6 text-gray-300" />
            </div>
            <p className="text-xs font-medium text-gray-500">No data sources connected</p>
            <p className="text-[11px] text-gray-400 mt-1">Click Add to connect your first source</p>
          </div>
        )}

        {connectors.map(connector => {
          const meta = CONNECTOR_META[connector.connectorType] ?? { label: connector.connectorType, bg: 'bg-gray-100' }
          const Icon = meta.Icon
          const isActive = activeConnectors.includes(connector.id)

          return (
            <motion.div
              key={connector.id}
              layout
              onClick={() => connector.enabled && toggleConnector(connector.id)}
              className={`relative rounded-xl border p-3 cursor-pointer transition-all select-none
                ${isActive
                  ? 'bg-blue-50 border-blue-200 shadow-sm'
                  : 'bg-white border-gray-100 hover:border-gray-200 hover:shadow-sm'}`}
            >
              <div className="flex items-center gap-2.5">
                {/* Brand icon */}
                <div className={`flex-shrink-0 w-9 h-9 rounded-xl flex items-center justify-center ${meta.bg}`}>
                  {Icon ? <Icon size={20} /> : <span className="text-base">🔌</span>}
                </div>

                {/* Name + type */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-1">
                    <p className="text-xs font-semibold text-gray-800 truncate">{connector.name}</p>
                    {connector.readOnly && connector.connectorType !== 'DOCUMENTS' && (
                      <Lock className="h-3 w-3 text-amber-500 flex-shrink-0" title="Read Only — writes blocked" />
                    )}
                  </div>
                  <p className="text-[10px] text-gray-400">{meta.label}</p>
                </div>

                {/* Status + detail button */}
                <div className="flex items-center gap-1.5 flex-shrink-0">
                  <StatusDot connector={connector} />
                  <button
                    onClick={e => { e.stopPropagation(); setDetailConnector(connector) }}
                    title="View details & ingestion status"
                    className="p-1 rounded-lg text-gray-400 hover:text-blue-600 hover:bg-blue-50 transition"
                  >
                    <BarChart2 className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>

              {/* Last sync */}
              {connector.lastSyncAt && (
                <div className="flex items-center gap-1 mt-1.5 pl-11">
                  <Clock className="h-2.5 w-2.5 text-gray-300" />
                  <span className="text-[10px] text-gray-400">
                    {formatDistanceToNow(new Date(connector.lastSyncAt), { addSuffix: true })}
                  </span>
                </div>
              )}

              {/* Active indicator */}
              {isActive && (
                <span className="absolute top-2.5 right-2.5 block h-1.5 w-1.5 rounded-full bg-blue-500" />
              )}
            </motion.div>
          )
        })}
      </div>

      {showAddModal && (
        <ConnectorConfigModal
          connector={editConnector}
          onClose={() => { setShowAddModal(false); setEditConnector(null) }}
          onSaved={() => { setShowAddModal(false); setEditConnector(null); loadConnectors() }}
        />
      )}

      {detailConnector && (
        <DataSourceDetailModal
          connector={detailConnector}
          onClose={() => setDetailConnector(null)}
          onEdit={c => {
            setDetailConnector(null)
            setEditConnector(c)
            setShowAddModal(true)
          }}
          onDeleted={() => { setDetailConnector(null); loadConnectors() }}
        />
      )}
    </div>
  )
}
