import { useEffect, useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import {
  Search, RefreshCw, Trash2, ChevronRight, X,
  GitBranch, FileText, User, Code, Lightbulb, BookOpen, Layers,
  AlertTriangle
} from 'lucide-react'
import useKgStore from '../../store/kgStore'
import { format } from 'date-fns'
import toast from 'react-hot-toast'

const ENTITY_ICONS = {
  PERSON:          User,
  CODE_COMPONENT:  Code,
  FUNCTION:        Code,
  CLASS_DEF:       Code,
  REPOSITORY:      GitBranch,
  DOCUMENT:        FileText,
  CONCEPT:         Lightbulb,
  TICKET:          BookOpen,
  STORY:           BookOpen,
  MODULE:          Layers,
  API_ENDPOINT:    Layers,
}

const ENTITY_COLORS = {
  PERSON:          'bg-purple-100 text-purple-700',
  CODE_COMPONENT:  'bg-blue-100 text-blue-700',
  FUNCTION:        'bg-blue-100 text-blue-700',
  CLASS_DEF:       'bg-indigo-100 text-indigo-700',
  REPOSITORY:      'bg-gray-100 text-gray-700',
  DOCUMENT:        'bg-orange-100 text-orange-700',
  CONCEPT:         'bg-yellow-100 text-yellow-700',
  TICKET:          'bg-red-100 text-red-700',
  STORY:           'bg-red-100 text-red-700',
  MODULE:          'bg-teal-100 text-teal-700',
  API_ENDPOINT:    'bg-green-100 text-green-700',
}

function EntityTypeBadge({ type }) {
  const cls = ENTITY_COLORS[type] ?? 'bg-gray-100 text-gray-500'
  const label = type?.replace('_', ' ') ?? 'UNKNOWN'
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[9px] font-bold tracking-wide ${cls}`}>
      {label}
    </span>
  )
}

function RelationshipLine({ rel, entityMap }) {
  const src = entityMap[rel.sourceEntityId] ?? rel.sourceEntityId
  const tgt = entityMap[rel.targetEntityId] ?? rel.targetEntityId
  const srcName = typeof src === 'object' ? src.name : src
  const tgtName = typeof tgt === 'object' ? tgt.name : tgt
  return (
    <div className="flex items-center gap-1.5 text-[10px] text-gray-600 py-0.5">
      <span className="font-medium text-gray-700 truncate max-w-[90px]">{srcName}</span>
      <span className="text-gray-300">→</span>
      <span className="inline-flex items-center rounded px-1 py-0.5 bg-gray-100 text-[9px] font-semibold text-gray-500 whitespace-nowrap">
        {rel.relationshipType}
      </span>
      <span className="text-gray-300">→</span>
      <span className="font-medium text-gray-700 truncate max-w-[90px]">{tgtName}</span>
    </div>
  )
}

function ClearAllConfirmDialog({ onConfirm, onCancel, clearing }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <motion.div
        initial={{ opacity: 0, scale: 0.95 }}
        animate={{ opacity: 1, scale: 1 }}
        className="w-full max-w-sm rounded-2xl bg-white shadow-2xl p-6"
      >
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 rounded-xl bg-red-100 flex items-center justify-center flex-shrink-0">
            <AlertTriangle className="h-5 w-5 text-red-600" />
          </div>
          <div>
            <h3 className="text-sm font-bold text-gray-900">Clear All Knowledge Graph Data</h3>
            <p className="text-xs text-gray-500 mt-0.5">This action cannot be undone</p>
          </div>
        </div>
        <p className="text-xs text-gray-600 mb-5 leading-relaxed">
          This will permanently delete <strong>all entities, relationships</strong> from MongoDB,
          all <strong>Neo4j graph nodes</strong>, all <strong>Pinecone vector embeddings</strong>,
          and all indexed document records for your account.
        </p>
        <div className="flex gap-2">
          <button
            onClick={onCancel}
            disabled={clearing}
            className="flex-1 rounded-xl border border-gray-200 py-2 text-xs font-semibold text-gray-700 hover:bg-gray-50 transition disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={onConfirm}
            disabled={clearing}
            className="flex-1 rounded-xl bg-red-600 py-2 text-xs font-semibold text-white hover:bg-red-700 transition disabled:opacity-50 flex items-center justify-center gap-1.5"
          >
            {clearing ? <><RefreshCw className="h-3 w-3 animate-spin" /> Clearing…</> : 'Yes, Clear Everything'}
          </button>
        </div>
      </motion.div>
    </div>
  )
}

export default function KnowledgeGraphPanel() {
  const {
    stats, statsLoading,
    entities, entitiesLoading, entityFilter,
    selectedEntity, neighbours, neighboursLoading,
    fetchStats, fetchEntities, selectEntity, clearSelectedEntity, deleteEntity,
    clearAllData,
  } = useKgStore()

  const [searchInput, setSearchInput] = useState('')
  const [typeFilter, setTypeFilter] = useState('')
  const [showClearConfirm, setShowClearConfirm] = useState(false)
  const [clearing, setClearing] = useState(false)

  const handleClearAll = async () => {
    setClearing(true)
    try {
      await clearAllData()
      toast.success('All knowledge graph data cleared successfully')
      setShowClearConfirm(false)
      fetchStats()
    } catch {
      toast.error('Failed to clear knowledge graph data')
    } finally {
      setClearing(false)
    }
  }

  useEffect(() => {
    fetchStats()
    fetchEntities()
  }, [])

  const handleSearch = (e) => {
    e.preventDefault()
    fetchEntities({ search: searchInput, type: typeFilter })
  }

  const handleTypeFilter = (t) => {
    setTypeFilter(t)
    fetchEntities({ type: t, search: searchInput })
  }

  const entityTypes = ['', 'CODE_COMPONENT','FUNCTION','TICKET','DOCUMENT','PERSON','CONCEPT','REPOSITORY','MODULE','API_ENDPOINT']

  const neighbourEntityMap = neighbours
    ? Object.fromEntries(neighbours.entities.map(e => [e.id, e]))
    : {}

  return (
    <>
    {showClearConfirm && (
      <ClearAllConfirmDialog
        onConfirm={handleClearAll}
        onCancel={() => setShowClearConfirm(false)}
        clearing={clearing}
      />
    )}
    <div className="flex h-full overflow-hidden">
      {/* Left panel — Entity list */}
      <div className="w-80 border-r border-gray-100 flex flex-col flex-shrink-0 overflow-hidden">
        {/* Stats bar */}
        <div className="px-4 py-3 border-b border-gray-100 bg-gradient-to-r from-indigo-50 to-blue-50">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-semibold text-gray-700">Knowledge Graph</span>
            <div className="flex items-center gap-1">
              <button
                onClick={() => setShowClearConfirm(true)}
                title="Clear all knowledge graph data"
                className="p-1 rounded-lg text-gray-400 hover:text-red-500 hover:bg-red-50 transition"
              >
                <Trash2 className="h-3 w-3" />
              </button>
              <button
                onClick={() => { fetchStats(); fetchEntities(entityFilter) }}
                className="p-1 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-white/60 transition"
              >
                <RefreshCw className={`h-3 w-3 ${statsLoading ? 'animate-spin' : ''}`} />
              </button>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div className="bg-white/70 rounded-xl p-2 text-center">
              <p className="text-lg font-bold text-indigo-700">{stats?.totalEntities ?? '—'}</p>
              <p className="text-[9px] text-gray-400">Entities</p>
            </div>
            <div className="bg-white/70 rounded-xl p-2 text-center">
              <p className="text-lg font-bold text-blue-700">{stats?.totalRelationships ?? '—'}</p>
              <p className="text-[9px] text-gray-400">Relationships</p>
            </div>
          </div>
        </div>

        {/* Search + filter */}
        <div className="px-3 py-2 border-b border-gray-100 space-y-2">
          <form onSubmit={handleSearch} className="flex gap-1.5">
            <input
              type="text"
              value={searchInput}
              onChange={e => setSearchInput(e.target.value)}
              placeholder="Search entities…"
              className="flex-1 text-xs border border-gray-200 rounded-lg px-2.5 py-1.5 outline-none focus:ring-1 focus:ring-indigo-300"
            />
            <button type="submit" className="p-1.5 rounded-lg bg-indigo-50 hover:bg-indigo-100 transition">
              <Search className="h-3.5 w-3.5 text-indigo-600" />
            </button>
          </form>
          <div className="flex gap-1 flex-wrap">
            {entityTypes.slice(0, 5).map(t => (
              <button
                key={t}
                onClick={() => handleTypeFilter(t)}
                className={`text-[9px] rounded-full px-2 py-0.5 font-semibold transition ${
                  typeFilter === t
                    ? 'bg-indigo-600 text-white'
                    : 'bg-gray-100 text-gray-500 hover:bg-gray-200'
                }`}
              >
                {t || 'All'}
              </button>
            ))}
          </div>
          <div className="flex gap-1 flex-wrap">
            {entityTypes.slice(5).map(t => (
              <button
                key={t}
                onClick={() => handleTypeFilter(t)}
                className={`text-[9px] rounded-full px-2 py-0.5 font-semibold transition ${
                  typeFilter === t
                    ? 'bg-indigo-600 text-white'
                    : 'bg-gray-100 text-gray-500 hover:bg-gray-200'
                }`}
              >
                {t || 'All'}
              </button>
            ))}
          </div>
        </div>

        {/* Entity list */}
        <div className="flex-1 overflow-y-auto">
          {entitiesLoading ? (
            <div className="py-10 text-center">
              <RefreshCw className="h-5 w-5 mx-auto text-gray-300 animate-spin mb-2" />
              <p className="text-xs text-gray-400">Loading entities…</p>
            </div>
          ) : entities.length === 0 ? (
            <div className="py-10 text-center px-4">
              <Layers className="h-8 w-8 mx-auto mb-2 text-gray-200" />
              <p className="text-xs text-gray-400">No entities in knowledge graph yet</p>
              <p className="text-[10px] text-gray-300 mt-1">Upload documents or connect data sources to build the graph</p>
            </div>
          ) : (
            <div className="py-1">
              {entities.slice(0, 100).map(entity => {
                const EntityIcon = ENTITY_ICONS[entity.entityType] ?? Layers
                const isSelected = selectedEntity?.id === entity.id
                return (
                  <button
                    key={entity.id}
                    onClick={() => selectEntity(entity)}
                    className={`w-full flex items-center gap-2.5 px-3 py-2.5 text-left transition border-b border-gray-50 hover:bg-indigo-50/60 ${
                      isSelected ? 'bg-indigo-50 border-l-2 border-l-indigo-500' : ''
                    }`}
                  >
                    <div className={`w-6 h-6 rounded-lg flex items-center justify-center flex-shrink-0 ${ENTITY_COLORS[entity.entityType] ?? 'bg-gray-100'}`}>
                      <EntityIcon className="h-3.5 w-3.5" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-xs font-medium text-gray-800 truncate">{entity.name}</p>
                      <div className="flex items-center gap-1.5 mt-0.5">
                        <EntityTypeBadge type={entity.entityType} />
                        {entity.sourceType && (
                          <span className="text-[9px] text-gray-400">{entity.sourceType}</span>
                        )}
                      </div>
                    </div>
                    <ChevronRight className="h-3.5 w-3.5 text-gray-300 flex-shrink-0" />
                  </button>
                )
              })}
              {entities.length > 100 && (
                <p className="text-[10px] text-center text-gray-400 py-3">
                  Showing 100 of {entities.length} entities
                </p>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Right panel — Entity detail + neighbourhood */}
      <div className="flex-1 overflow-y-auto p-6">
        <AnimatePresence mode="wait">
          {selectedEntity ? (
            <motion.div
              key={selectedEntity.id}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              className="max-w-2xl"
            >
              {/* Entity header */}
              <div className="flex items-start gap-3 mb-6">
                <div className={`w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 ${ENTITY_COLORS[selectedEntity.entityType] ?? 'bg-gray-100'}`}>
                  {(() => { const I = ENTITY_ICONS[selectedEntity.entityType] ?? Layers; return <I className="h-5 w-5" /> })()}
                </div>
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <h2 className="text-base font-bold text-gray-900">{selectedEntity.name}</h2>
                    <EntityTypeBadge type={selectedEntity.entityType} />
                  </div>
                  {selectedEntity.description && (
                    <p className="text-sm text-gray-500 mt-1">{selectedEntity.description}</p>
                  )}
                  <div className="flex gap-3 mt-1.5 text-[10px] text-gray-400">
                    {selectedEntity.sourceType && <span>Source: {selectedEntity.sourceType}</span>}
                    {selectedEntity.sourceRef && <span>Ref: {selectedEntity.sourceRef}</span>}
                    {selectedEntity.updatedAt && (
                      <span>Updated: {format(new Date(selectedEntity.updatedAt), 'MMM d, yyyy')}</span>
                    )}
                  </div>
                </div>
                <div className="flex items-center gap-1.5 flex-shrink-0">
                  <button
                    onClick={() => deleteEntity(selectedEntity.id)}
                    className="p-1.5 rounded-lg text-gray-400 hover:text-red-500 hover:bg-red-50 transition"
                    title="Remove entity from knowledge graph"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                  <button
                    onClick={clearSelectedEntity}
                    className="p-1.5 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>

              {/* Properties */}
              {selectedEntity.properties && Object.keys(selectedEntity.properties).length > 0 && (
                <div className="mb-5 rounded-xl border border-gray-100 bg-gray-50 p-4">
                  <h3 className="text-xs font-semibold text-gray-600 mb-2">Properties</h3>
                  <dl className="grid grid-cols-2 gap-x-4 gap-y-1">
                    {Object.entries(selectedEntity.properties).map(([k, v]) => (
                      <div key={k}>
                        <dt className="text-[10px] text-gray-400">{k}</dt>
                        <dd className="text-xs text-gray-700 truncate">{String(v)}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
              )}

              {/* Neighbourhood */}
              <div>
                <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
                  <GitBranch className="h-4 w-4 text-indigo-400" />
                  Graph Neighbourhood (1-hop)
                  {neighboursLoading && <RefreshCw className="h-3 w-3 text-gray-300 animate-spin" />}
                </h3>

                {neighbours && !neighboursLoading && (
                  <>
                    {/* Relationships */}
                    {neighbours.relationships.length > 0 && (
                      <div className="mb-4 rounded-xl border border-gray-100 bg-gray-50 p-3">
                        <p className="text-[10px] font-semibold text-gray-500 mb-2">
                          Relationships ({neighbours.relationships.length})
                        </p>
                        <div className="space-y-0.5">
                          {neighbours.relationships.slice(0, 20).map(rel => (
                            <RelationshipLine key={rel.id} rel={rel} entityMap={neighbourEntityMap} />
                          ))}
                        </div>
                      </div>
                    )}

                    {/* Neighbour entities */}
                    {neighbours.entities.filter(e => e.id !== selectedEntity.id).length > 0 && (
                      <div className="rounded-xl border border-gray-100 bg-gray-50 p-3">
                        <p className="text-[10px] font-semibold text-gray-500 mb-2">
                          Connected Entities ({neighbours.entities.length - 1})
                        </p>
                        <div className="space-y-1.5">
                          {neighbours.entities
                            .filter(e => e.id !== selectedEntity.id)
                            .slice(0, 15)
                            .map(entity => {
                              const EI = ENTITY_ICONS[entity.entityType] ?? Layers
                              return (
                                <button
                                  key={entity.id}
                                  onClick={() => selectEntity(entity)}
                                  className="w-full flex items-center gap-2 rounded-lg p-2 bg-white hover:bg-indigo-50 transition text-left"
                                >
                                  <div className={`w-5 h-5 rounded flex items-center justify-center ${ENTITY_COLORS[entity.entityType] ?? 'bg-gray-100'}`}>
                                    <EI className="h-3 w-3" />
                                  </div>
                                  <span className="text-xs font-medium text-gray-700 truncate flex-1">{entity.name}</span>
                                  <EntityTypeBadge type={entity.entityType} />
                                </button>
                              )
                            })}
                        </div>
                      </div>
                    )}

                    {neighbours.entities.length === 1 && neighbours.relationships.length === 0 && (
                      <p className="text-xs text-gray-400 py-4 text-center">No connected entities found</p>
                    )}
                  </>
                )}
              </div>
            </motion.div>
          ) : (
            <motion.div
              key="empty"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              className="h-full flex flex-col items-center justify-center text-center py-20"
            >
              <div className="w-16 h-16 rounded-2xl bg-indigo-50 flex items-center justify-center mb-4">
                <GitBranch className="h-8 w-8 text-indigo-300" />
              </div>
              <h3 className="text-sm font-semibold text-gray-700">Select an entity</h3>
              <p className="text-xs text-gray-400 mt-1 max-w-xs">
                Click an entity from the list to explore its properties and graph neighbourhood
              </p>
              {stats && (
                <div className="mt-6 flex gap-3">
                  {Object.entries(stats.entitiesByType ?? {}).map(([type, count]) => (
                    <div key={type} className={`rounded-xl px-3 py-2 text-center ${ENTITY_COLORS[type] ?? 'bg-gray-100'}`}>
                      <p className="text-sm font-bold">{count}</p>
                      <p className="text-[9px] font-semibold">{type.replace('_',' ')}</p>
                    </div>
                  ))}
                </div>
              )}
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
    </>
  )
}
