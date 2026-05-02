import { useMemo } from 'react'
import useSkgStore from '../../store/skgStore'

const NODE_COLORS = {
  Service:        '#4C8BF5', Module: '#00BCD4', Component: '#8BC34A',
  Class:          '#9C27B0', Function: '#FF5722', Api: '#FF9800',
  Database:       '#F44336', Table: '#E91E63', Document: '#795548',
  DesignDoc:      '#607D8B', Section: '#9E9E9E', Repository_Node: '#3F51B5',
  Story:          '#009688', Bug: '#F44336', Task: '#78909C',
}

const LAYER_LABELS = { SYSTEM: 'System', CODE: 'Code', DOCUMENTATION: 'Documentation', WORK: 'Work Items' }

// ── Layered View — groups nodes by layer & type, shows degree ──────────────
export function LayeredView() {
  const { graphData, selectNode } = useSkgStore()
  const nodes = graphData?.nodes || []
  const edges = graphData?.edges || []

  const degree = useMemo(() => {
    const d = {}
    edges.forEach(e => { d[e.sourceId] = (d[e.sourceId] || 0) + 1; d[e.targetId] = (d[e.targetId] || 0) + 1 })
    return d
  }, [edges])

  const grouped = useMemo(() => {
    const out = {}
    nodes.forEach(n => {
      const layer = n.layer || 'OTHER'
      const type = n.nodeType || 'Unknown'
      out[layer] = out[layer] || {}
      out[layer][type] = out[layer][type] || []
      out[layer][type].push(n)
    })
    return out
  }, [nodes])

  if (!nodes.length) return <EmptyHint />

  return (
    <div className="p-6 space-y-6 overflow-y-auto h-full bg-gray-50">
      {Object.entries(grouped).map(([layer, types]) => (
        <div key={layer} className="bg-white rounded-2xl border border-gray-200 p-5">
          <h3 className="text-sm font-bold text-gray-900 mb-4 flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-indigo-500" />
            {LAYER_LABELS[layer] || layer}
            <span className="text-xs font-normal text-gray-400">
              ({Object.values(types).reduce((s, arr) => s + arr.length, 0)})
            </span>
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {Object.entries(types).map(([type, list]) => (
              <div key={type} className="rounded-xl border border-gray-100 p-3">
                <div className="flex items-center gap-2 mb-2">
                  <span className="w-2 h-2 rounded-full" style={{ backgroundColor: NODE_COLORS[type] || '#999' }} />
                  <span className="text-xs font-semibold text-gray-700">{type}</span>
                  <span className="text-[10px] text-gray-400 ml-auto">{list.length}</span>
                </div>
                <div className="space-y-1">
                  {list.slice(0, 6).map(n => (
                    <button
                      key={n.id}
                      onClick={() => selectNode(n)}
                      className="w-full flex items-center justify-between text-left px-2 py-1 rounded hover:bg-indigo-50 transition"
                    >
                      <span className="text-[11px] text-gray-700 truncate">{n.name}</span>
                      <span className="text-[9px] text-gray-400">{degree[n.id] || 0}↔</span>
                    </button>
                  ))}
                  {list.length > 6 && <p className="text-[10px] text-gray-400 px-2">+{list.length - 6} more</p>}
                </div>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

// ── Dependency Matrix — relationship counts between node types ──────────────
export function MatrixView() {
  const { graphData } = useSkgStore()
  const nodes = graphData?.nodes || []
  const edges = graphData?.edges || []

  const { types, matrix } = useMemo(() => {
    const idToType = {}
    nodes.forEach(n => { idToType[n.id] = n.nodeType })
    const set = new Set()
    const m = {}
    edges.forEach(e => {
      const s = idToType[e.sourceId], t = idToType[e.targetId]
      if (!s || !t) return
      set.add(s); set.add(t)
      m[s] = m[s] || {}
      m[s][t] = (m[s][t] || 0) + 1
    })
    return { types: Array.from(set).sort(), matrix: m }
  }, [nodes, edges])

  if (!nodes.length) return <EmptyHint />

  const max = Math.max(1, ...types.flatMap(s => types.map(t => matrix[s]?.[t] || 0)))

  return (
    <div className="p-6 overflow-auto h-full bg-gray-50">
      <h3 className="text-sm font-bold text-gray-900 mb-4">Dependency Matrix</h3>
      <p className="text-xs text-gray-500 mb-4">
        Shows how many edges exist between each pair of node types. Darker = more dependencies.
      </p>
      <div className="bg-white rounded-2xl border border-gray-200 p-4 inline-block">
        <table className="border-separate border-spacing-1">
          <thead>
            <tr>
              <th className="w-24"></th>
              {types.map(t => (
                <th key={t} className="text-[10px] font-semibold text-gray-600 px-2 py-1 rotate-[-30deg] origin-bottom-left whitespace-nowrap">
                  {t}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {types.map(s => (
              <tr key={s}>
                <td className="text-[10px] font-semibold text-gray-700 pr-2">
                  <span className="inline-block w-2 h-2 rounded-full mr-1" style={{ backgroundColor: NODE_COLORS[s] || '#999' }} />
                  {s}
                </td>
                {types.map(t => {
                  const v = matrix[s]?.[t] || 0
                  const intensity = v / max
                  return (
                    <td
                      key={t}
                      className="w-9 h-9 text-center text-[10px] font-mono rounded"
                      style={{
                        backgroundColor: v ? `rgba(99, 102, 241, ${0.15 + intensity * 0.7})` : '#f3f4f6',
                        color: intensity > 0.5 ? '#fff' : '#374151',
                      }}
                      title={`${s} → ${t}: ${v}`}
                    >
                      {v || ''}
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

// ── Tree View — hierarchical CONTAINS chains starting from Services ─────────
export function TreeView() {
  const { graphData, selectNode } = useSkgStore()
  const nodes = graphData?.nodes || []
  const edges = graphData?.edges || []

  const tree = useMemo(() => {
    const idToNode = Object.fromEntries(nodes.map(n => [n.id, n]))
    const children = {}
    edges.forEach(e => {
      if (['CONTAINS', 'EXPOSED_BY', 'HAS_FIELD', 'IMPLEMENTS'].includes(e.relType)) {
        children[e.sourceId] = children[e.sourceId] || []
        children[e.sourceId].push(e.targetId)
      }
    })
    const childIds = new Set(Object.values(children).flat())
    const roots = nodes.filter(n => !childIds.has(n.id) && (children[n.id] || []).length > 0)
    return { idToNode, children, roots }
  }, [nodes, edges])

  if (!nodes.length) return <EmptyHint />

  const renderNode = (id, depth = 0, seen = new Set()) => {
    if (seen.has(id) || depth > 6) return null
    seen.add(id)
    const node = tree.idToNode[id]
    if (!node) return null
    const kids = tree.children[id] || []
    return (
      <div key={id + '-' + depth} style={{ paddingLeft: depth * 16 }}>
        <button
          onClick={() => selectNode(node)}
          className="flex items-center gap-2 px-2 py-1 rounded hover:bg-indigo-50 transition w-full text-left"
        >
          <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: NODE_COLORS[node.nodeType] || '#999' }} />
          <span className="text-xs text-gray-700 truncate">{node.name}</span>
          <span className="text-[9px] text-gray-400">{node.nodeType}</span>
        </button>
        {kids.map(k => renderNode(k, depth + 1, new Set(seen)))}
      </div>
    )
  }

  return (
    <div className="p-6 overflow-auto h-full bg-gray-50">
      <h3 className="text-sm font-bold text-gray-900 mb-4">Containment Tree</h3>
      <p className="text-xs text-gray-500 mb-4">
        Hierarchical view following CONTAINS / EXPOSED_BY / HAS_FIELD / IMPLEMENTS edges.
      </p>
      <div className="bg-white rounded-2xl border border-gray-200 p-4 space-y-1">
        {tree.roots.length
          ? tree.roots.map(r => renderNode(r.id))
          : <p className="text-xs text-gray-400">No hierarchical structure detected yet.</p>}
      </div>
    </div>
  )
}

function EmptyHint() {
  return (
    <div className="h-full flex items-center justify-center">
      <p className="text-xs text-gray-400">No graph data — refresh the knowledge graph to populate.</p>
    </div>
  )
}
