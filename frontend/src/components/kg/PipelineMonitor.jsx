import { useEffect } from 'react'
import { RefreshCw, CheckCircle2, XCircle, Clock, Activity, Database, AlertTriangle } from 'lucide-react'
import useKgStore from '../../store/kgStore'
import { format, formatDistanceToNow } from 'date-fns'

const CDC_STATUS_META = {
  IDLE:      { cls: 'bg-gray-100 text-gray-500',               label: 'Idle',       Icon: Clock },
  RUNNING:   { cls: 'bg-blue-100 text-blue-700 animate-pulse', label: 'Running',    Icon: Activity },
  COMPLETED: { cls: 'bg-green-100 text-green-700',             label: 'Completed',  Icon: CheckCircle2 },
  FAILED:    { cls: 'bg-red-100 text-red-700',                 label: 'Failed',     Icon: XCircle },
}

function CdcCard({ state }) {
  const sm = CDC_STATUS_META[state.status] ?? CDC_STATUS_META.IDLE
  const StatusIcon = sm.Icon
  return (
    <div className="rounded-xl border border-gray-100 bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between mb-3">
        <div>
          <p className="text-sm font-semibold text-gray-800">{state.connectorType}</p>
          <p className="text-[10px] text-gray-400 mt-0.5 font-mono truncate max-w-[200px]">
            {state.connectorId}
          </p>
        </div>
        <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold ${sm.cls}`}>
          <StatusIcon className="h-2.5 w-2.5" />
          {sm.label}
        </span>
      </div>

      <div className="grid grid-cols-2 gap-3 mb-3">
        <div className="rounded-lg bg-gray-50 p-2 text-center">
          <p className="text-base font-bold text-gray-800">
            {state.totalRecordsProcessed?.toLocaleString() ?? 0}
          </p>
          <p className="text-[9px] text-gray-400">Total Processed</p>
        </div>
        <div className="rounded-lg bg-gray-50 p-2 text-center">
          <p className="text-base font-bold text-gray-800">
            {state.lastBatchChanged?.toLocaleString() ?? 0}
          </p>
          <p className="text-[9px] text-gray-400">Last Batch Changed</p>
        </div>
      </div>

      <div className="space-y-1 text-[10px] text-gray-500">
        {state.lastSyncAt && (
          <div className="flex items-center gap-1.5">
            <CheckCircle2 className="h-2.5 w-2.5 text-green-400" />
            Last sync: {formatDistanceToNow(new Date(state.lastSyncAt), { addSuffix: true })}
          </div>
        )}
        {state.lastCursor && (
          <div className="flex items-center gap-1.5">
            <Database className="h-2.5 w-2.5 text-gray-300" />
            Cursor: <span className="font-mono truncate">{state.lastCursor}</span>
          </div>
        )}
        {state.lastError && (
          <div className="flex items-start gap-1.5 text-red-500 mt-1">
            <AlertTriangle className="h-2.5 w-2.5 flex-shrink-0 mt-px" />
            <span className="truncate">{state.lastError}</span>
          </div>
        )}
        <div className="flex items-center gap-1.5 text-gray-300">
          <Clock className="h-2.5 w-2.5" />
          Updated: {state.updatedAt ? format(new Date(state.updatedAt), 'MMM d, HH:mm') : '—'}
        </div>
      </div>
    </div>
  )
}

export default function PipelineMonitor() {
  const { stats, statsLoading, cdcStates, cdcLoading, fetchStats, fetchCdcStates } = useKgStore()

  useEffect(() => {
    fetchStats()
    fetchCdcStates()
  }, [])

  const handleRefresh = () => {
    fetchStats()
    fetchCdcStates()
  }

  const activeSyncs = cdcStates.filter(s => s.status === 'RUNNING').length
  const failedSyncs = cdcStates.filter(s => s.status === 'FAILED').length

  return (
    <div className="p-6 max-w-5xl">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h2 className="text-base font-bold text-gray-900">Pipeline Monitor</h2>
          <p className="text-xs text-gray-400 mt-0.5">
            CDC sync states and ingestion pipeline health
          </p>
        </div>
        <button
          onClick={handleRefresh}
          className="flex items-center gap-1.5 rounded-xl border border-gray-200 bg-white px-3 py-2 text-xs font-medium text-gray-700 hover:bg-gray-50 transition"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${cdcLoading || statsLoading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>

      {/* KG Overview */}
      <div className="grid grid-cols-4 gap-3 mb-6">
        {[
          { label: 'Total Entities',       value: stats?.totalEntities,       color: 'text-indigo-700' },
          { label: 'Total Relationships',  value: stats?.totalRelationships,  color: 'text-blue-700'   },
          { label: 'Active Syncs',         value: activeSyncs,                color: 'text-green-700'  },
          { label: 'Failed Syncs',         value: failedSyncs,                color: failedSyncs > 0 ? 'text-red-600' : 'text-gray-800' },
        ].map(({ label, value, color }) => (
          <div key={label} className="rounded-xl border border-gray-100 bg-white p-4 shadow-sm text-center">
            <p className={`text-2xl font-bold ${color}`}>{value?.toLocaleString() ?? '—'}</p>
            <p className="text-[10px] text-gray-400 mt-0.5">{label}</p>
          </div>
        ))}
      </div>

      {/* Entity type breakdown */}
      {stats?.entitiesByType && Object.keys(stats.entitiesByType).length > 0 && (
        <div className="mb-6 rounded-xl border border-gray-100 bg-white p-4 shadow-sm">
          <h3 className="text-xs font-semibold text-gray-600 mb-3">Entity Type Distribution</h3>
          <div className="flex flex-wrap gap-2">
            {Object.entries(stats.entitiesByType).map(([type, count]) => (
              <div key={type} className="flex items-center gap-1.5 rounded-lg bg-gray-50 px-3 py-1.5">
                <span className="text-xs font-semibold text-gray-700">{count}</span>
                <span className="text-[10px] text-gray-400">{type.replace('_', ' ')}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* CDC Sync States */}
      <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
        <Activity className="h-4 w-4 text-indigo-400" />
        CDC Sync States
        {cdcStates.length > 0 && (
          <span className="text-[10px] text-gray-400">· {cdcStates.length} connector{cdcStates.length !== 1 ? 's' : ''}</span>
        )}
      </h3>

      {cdcLoading && cdcStates.length === 0 ? (
        <div className="py-10 text-center">
          <RefreshCw className="h-6 w-6 mx-auto mb-2 text-gray-300 animate-spin" />
          <p className="text-xs text-gray-400">Loading sync states…</p>
        </div>
      ) : cdcStates.length === 0 ? (
        <div className="rounded-xl border border-dashed border-gray-200 py-12 text-center">
          <Database className="h-8 w-8 mx-auto mb-2 text-gray-200" />
          <p className="text-xs text-gray-400">No CDC sync states yet</p>
          <p className="text-[10px] text-gray-300 mt-1">
            Connect data sources and upload documents to start building the knowledge graph
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
          {cdcStates.map(state => (
            <CdcCard key={state.id} state={state} />
          ))}
        </div>
      )}
    </div>
  )
}
