import { useEffect, useRef, useCallback, useState } from 'react'
import { Search, ZoomIn, ZoomOut, RefreshCw, Layers, Filter, X } from 'lucide-react'
import useSkgStore from '../../store/skgStore'

// ── Node type config ──────────────────────────────────────────────────────────
const NODE_COLORS = {
  Service:        '#4C8BF5',
  Module:         '#00BCD4',
  Component:      '#8BC34A',
  Class:          '#9C27B0',
  Function:       '#FF5722',
  Api:            '#FF9800',
  Database:       '#F44336',
  Table:          '#E91E63',
  Document:       '#795548',
  DesignDoc:      '#607D8B',
  Section:        '#9E9E9E',
  Repository_Node:'#3F51B5',
  Story:          '#009688',
  Bug:            '#F44336',
  Task:           '#78909C',
}

const NODE_RADIUS = {
  Service: 18, Module: 14, Component: 12, Api: 11,
  Database: 16, Document: 10, DesignDoc: 10,
  Class: 9, Function: 8, Story: 10, Bug: 10, Task: 8,
}

const LAYER_LABELS = { SYSTEM: 'System', CODE: 'Code', DOCUMENTATION: 'Docs', WORK: 'Work' }

// ── Main Component ────────────────────────────────────────────────────────────
export default function SystemGraphView() {
  const canvasRef = useRef(null)
  const animRef   = useRef(null)
  const simRef    = useRef({ nodes: [], edges: [], transform: { x: 0, y: 0, scale: 1 } })
  const dragRef   = useRef(null)
  const panRef    = useRef(null)

  const { graphData, graphLoading, selectedNode, selectNode, fetchGraph, graphFilter } = useSkgStore()

  const [layerFilter, setLayerFilter] = useState(null)
  const [searchQ,     setSearchQ]     = useState('')
  const [tooltip,     setTooltip]     = useState(null)
  const [hoveredNode, setHoveredNode] = useState(null)

  // ── Build simulation data ──────────────────────────────────────────────────
  useEffect(() => {
    if (!graphData) return
    const { nodes: rawNodes = [], edges: rawEdges = [] } = graphData

    // Filter by layer
    const visibleNodes = layerFilter
      ? rawNodes.filter(n => n.layer === layerFilter)
      : rawNodes

    const visibleIds = new Set(visibleNodes.map(n => n.id))
    const visibleEdges = rawEdges.filter(e => visibleIds.has(e.sourceId) && visibleIds.has(e.targetId))

    const canvas = canvasRef.current
    if (!canvas) return
    const W = canvas.width, H = canvas.height

    // Initialize node positions if new
    const existingPos = {}
    simRef.current.nodes.forEach(n => { existingPos[n.id] = { x: n.x, y: n.y } })

    const simNodes = visibleNodes.map(n => ({
      ...n,
      x: existingPos[n.id]?.x ?? (W / 2 + (Math.random() - 0.5) * 300),
      y: existingPos[n.id]?.y ?? (H / 2 + (Math.random() - 0.5) * 300),
      vx: 0, vy: 0,
    }))
    const idToNode = Object.fromEntries(simNodes.map(n => [n.id, n]))
    const simEdges = visibleEdges.map(e => ({
      ...e, source: idToNode[e.sourceId], target: idToNode[e.targetId],
    })).filter(e => e.source && e.target)

    simRef.current = { ...simRef.current, nodes: simNodes, edges: simEdges }
    startSimulation()
  }, [graphData, layerFilter])

  // ── Force simulation ───────────────────────────────────────────────────────
  const startSimulation = useCallback(() => {
    if (animRef.current) cancelAnimationFrame(animRef.current)

    let tick = 0
    const MAX_TICKS = 300

    const step = () => {
      const { nodes, edges } = simRef.current
      if (!nodes.length) return

      const alpha = Math.max(0.001, 0.3 * (1 - tick / MAX_TICKS))

      // Repulsion between all nodes
      for (let i = 0; i < nodes.length; i++) {
        for (let j = i + 1; j < nodes.length; j++) {
          const dx = nodes[j].x - nodes[i].x
          const dy = nodes[j].y - nodes[i].y
          const dist = Math.sqrt(dx * dx + dy * dy) || 1
          const force = (1200 / (dist * dist)) * alpha
          nodes[i].vx -= (dx / dist) * force
          nodes[i].vy -= (dy / dist) * force
          nodes[j].vx += (dx / dist) * force
          nodes[j].vy += (dy / dist) * force
        }
      }

      // Attraction along edges (spring)
      for (const e of edges) {
        if (!e.source || !e.target) continue
        const dx = e.target.x - e.source.x
        const dy = e.target.y - e.source.y
        const dist = Math.sqrt(dx * dx + dy * dy) || 1
        const target = 120
        const force = ((dist - target) / dist) * 0.06 * alpha
        e.source.vx += dx * force
        e.source.vy += dy * force
        e.target.vx -= dx * force
        e.target.vy -= dy * force
      }

      // Gravity toward center
      const canvas = canvasRef.current
      if (canvas) {
        const cx = canvas.width / 2, cy = canvas.height / 2
        nodes.forEach(n => {
          n.vx += (cx - n.x) * 0.002 * alpha
          n.vy += (cy - n.y) * 0.002 * alpha
        })
      }

      // Integrate
      nodes.forEach(n => {
        if (n._pinned) return
        n.vx *= 0.85
        n.vy *= 0.85
        n.x += n.vx
        n.y += n.vy
      })

      tick++
      draw()
      if (tick < MAX_TICKS || nodes.some(n => Math.abs(n.vx) > 0.1 || Math.abs(n.vy) > 0.1)) {
        animRef.current = requestAnimationFrame(step)
      }
    }

    animRef.current = requestAnimationFrame(step)
  }, [])

  // ── Canvas draw ────────────────────────────────────────────────────────────
  const draw = useCallback(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    const { nodes, edges, transform } = simRef.current
    const { x: tx, y: ty, scale } = transform

    ctx.clearRect(0, 0, canvas.width, canvas.height)
    ctx.save()
    ctx.translate(tx, ty)
    ctx.scale(scale, scale)

    // Draw edges
    for (const e of edges) {
      if (!e.source || !e.target) continue
      const isHighlighted = selectedNode &&
        (e.sourceId === selectedNode.id || e.targetId === selectedNode.id)
      ctx.beginPath()
      ctx.moveTo(e.source.x, e.source.y)
      ctx.lineTo(e.target.x, e.target.y)
      ctx.strokeStyle = isHighlighted ? '#6366f1' : 'rgba(156,163,175,0.4)'
      ctx.lineWidth = isHighlighted ? 2 : 1
      ctx.stroke()

      // Arrow
      const angle = Math.atan2(e.target.y - e.source.y, e.target.x - e.source.x)
      const r = NODE_RADIUS[e.target.nodeType] || 10
      const ax = e.target.x - Math.cos(angle) * (r + 4)
      const ay = e.target.y - Math.sin(angle) * (r + 4)
      ctx.beginPath()
      ctx.moveTo(ax, ay)
      ctx.lineTo(ax - 8 * Math.cos(angle - 0.4), ay - 8 * Math.sin(angle - 0.4))
      ctx.lineTo(ax - 8 * Math.cos(angle + 0.4), ay - 8 * Math.sin(angle + 0.4))
      ctx.closePath()
      ctx.fillStyle = isHighlighted ? '#6366f1' : 'rgba(156,163,175,0.4)'
      ctx.fill()

      // Edge label
      if (isHighlighted) {
        const mx = (e.source.x + e.target.x) / 2
        const my = (e.source.y + e.target.y) / 2
        ctx.font = '8px Inter, sans-serif'
        ctx.fillStyle = '#6366f1'
        ctx.textAlign = 'center'
        ctx.fillText(e.relType, mx, my - 4)
      }
    }

    // Draw nodes
    for (const node of nodes) {
      const r = NODE_RADIUS[node.nodeType] || 10
      const color = NODE_COLORS[node.nodeType] || '#6B7280'
      const isSelected = selectedNode?.id === node.id
      const isHovered  = hoveredNode?.id === node.id

      // Glow for selected
      if (isSelected) {
        ctx.beginPath()
        ctx.arc(node.x, node.y, r + 6, 0, Math.PI * 2)
        ctx.fillStyle = color + '33'
        ctx.fill()
      }

      // Node circle
      ctx.beginPath()
      ctx.arc(node.x, node.y, r, 0, Math.PI * 2)
      ctx.fillStyle = color
      ctx.fill()
      ctx.strokeStyle = isSelected ? '#fff' : (isHovered ? '#e5e7eb' : 'rgba(255,255,255,0.3)')
      ctx.lineWidth = isSelected ? 3 : 1.5
      ctx.stroke()

      // Label
      const label = (node.name || '').length > 14 ? node.name.substring(0, 12) + '…' : node.name
      ctx.font = `${isSelected ? 'bold ' : ''}${r > 12 ? '10' : '9'}px Inter, sans-serif`
      ctx.fillStyle = '#1f2937'
      ctx.textAlign = 'center'
      ctx.fillText(label, node.x, node.y + r + 12)

      // Type badge for large nodes
      if (r >= 14) {
        ctx.font = '7px Inter, sans-serif'
        ctx.fillStyle = color
        ctx.fillText(node.nodeType, node.x, node.y + r + 22)
      }
    }

    ctx.restore()
  }, [selectedNode, hoveredNode])

  // ── Mouse events ───────────────────────────────────────────────────────────
  const getNodeAt = useCallback((ex, ey) => {
    const { nodes, transform } = simRef.current
    const { x: tx, y: ty, scale } = transform
    const wx = (ex - tx) / scale
    const wy = (ey - ty) / scale
    return nodes.find(n => {
      const r = NODE_RADIUS[n.nodeType] || 10
      return Math.hypot(n.x - wx, n.y - wy) <= r + 4
    })
  }, [])

  const onMouseDown = useCallback((e) => {
    const canvas = canvasRef.current
    const rect = canvas.getBoundingClientRect()
    const ex = e.clientX - rect.left
    const ey = e.clientY - rect.top
    const hit = getNodeAt(ex, ey)
    if (hit) {
      dragRef.current = { node: hit, startX: ex, startY: ey, moved: false }
    } else {
      panRef.current = { startX: e.clientX, startY: e.clientY,
        tx: simRef.current.transform.x, ty: simRef.current.transform.y }
    }
  }, [getNodeAt])

  const onMouseMove = useCallback((e) => {
    const canvas = canvasRef.current
    const rect = canvas.getBoundingClientRect()
    const ex = e.clientX - rect.left
    const ey = e.clientY - rect.top
    const { transform } = simRef.current

    if (dragRef.current) {
      const dx = ex - dragRef.current.startX
      const dy = ey - dragRef.current.startY
      if (Math.abs(dx) > 3 || Math.abs(dy) > 3) dragRef.current.moved = true
      dragRef.current.node.x = (ex - transform.x) / transform.scale
      dragRef.current.node.y = (ey - transform.y) / transform.scale
      dragRef.current.node._pinned = true
      draw()
    } else if (panRef.current) {
      simRef.current.transform.x = panRef.current.tx + (e.clientX - panRef.current.startX)
      simRef.current.transform.y = panRef.current.ty + (e.clientY - panRef.current.startY)
      draw()
    } else {
      const hit = getNodeAt(ex, ey)
      if (hit !== hoveredNode) {
        setHoveredNode(hit || null)
        setTooltip(hit ? { node: hit, x: ex + 12, y: ey } : null)
        canvas.style.cursor = hit ? 'pointer' : 'default'
      }
    }
  }, [draw, getNodeAt, hoveredNode])

  const onMouseUp = useCallback((e) => {
    if (dragRef.current && !dragRef.current.moved) {
      selectNode(dragRef.current.node)
    }
    dragRef.current = null
    panRef.current  = null
  }, [selectNode])

  const onWheel = useCallback((e) => {
    e.preventDefault()
    const factor = e.deltaY < 0 ? 1.1 : 0.9
    const canvas = canvasRef.current
    const rect   = canvas.getBoundingClientRect()
    const ox = e.clientX - rect.left
    const oy = e.clientY - rect.top
    const { x: tx, y: ty, scale } = simRef.current.transform
    const newScale = Math.min(Math.max(0.1, scale * factor), 5)
    simRef.current.transform = {
      x: ox - (ox - tx) * (newScale / scale),
      y: oy - (oy - ty) * (newScale / scale),
      scale: newScale,
    }
    draw()
  }, [draw])

  // ── Canvas resize ──────────────────────────────────────────────────────────
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const resize = () => {
      canvas.width  = canvas.offsetWidth
      canvas.height = canvas.offsetHeight
      draw()
    }
    resize()
    const ro = new ResizeObserver(resize)
    ro.observe(canvas)
    return () => { ro.disconnect(); if (animRef.current) cancelAnimationFrame(animRef.current) }
  }, [draw])

  // ── Initial load ───────────────────────────────────────────────────────────
  useEffect(() => { fetchGraph() }, [])

  const zoom = (factor) => {
    const canvas = canvasRef.current
    const cx = canvas.width / 2, cy = canvas.height / 2
    const { x: tx, y: ty, scale } = simRef.current.transform
    const newScale = Math.min(Math.max(0.1, scale * factor), 5)
    simRef.current.transform = {
      x: cx - (cx - tx) * (newScale / scale),
      y: cy - (cy - ty) * (newScale / scale),
      scale: newScale,
    }
    draw()
  }

  const resetView = () => {
    simRef.current.transform = { x: 0, y: 0, scale: 1 }
    draw()
  }

  const nodeCount   = graphData?.totalNodes ?? 0
  const edgeCount   = graphData?.totalEdges ?? 0
  const byType      = graphData?.nodeCountByType ?? {}

  return (
    <div className="flex flex-col h-full bg-gray-950 relative select-none">
      {/* Toolbar */}
      <div className="absolute top-3 left-3 right-3 z-10 flex items-center gap-2 flex-wrap">
        {/* Stats pill */}
        <div className="bg-gray-900/90 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-3 border border-gray-800">
          <span className="text-xs font-bold text-indigo-400">{nodeCount} nodes</span>
          <span className="w-px h-3 bg-gray-700" />
          <span className="text-xs text-gray-400">{edgeCount} edges</span>
          {graphLoading && <RefreshCw className="h-3 w-3 text-gray-400 animate-spin" />}
        </div>

        {/* Layer filter */}
        <div className="bg-gray-900/90 backdrop-blur rounded-xl border border-gray-800 flex">
          {[null, 'SYSTEM', 'CODE', 'DOCUMENTATION', 'WORK'].map(l => (
            <button
              key={l ?? 'all'}
              onClick={() => setLayerFilter(l)}
              className={`px-2.5 py-1.5 text-[10px] font-semibold transition first:rounded-l-xl last:rounded-r-xl ${
                layerFilter === l
                  ? 'bg-indigo-600 text-white'
                  : 'text-gray-400 hover:text-gray-200'
              }`}
            >
              {l ? LAYER_LABELS[l] : 'All'}
            </button>
          ))}
        </div>

        {/* Node type legend */}
        <div className="bg-gray-900/90 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2 border border-gray-800 overflow-x-auto max-w-xs">
          {Object.entries(byType).slice(0, 6).map(([type, cnt]) => (
            <div key={type} className="flex items-center gap-1 flex-shrink-0">
              <div className="w-2 h-2 rounded-full" style={{ backgroundColor: NODE_COLORS[type] || '#6b7280' }} />
              <span className="text-[9px] text-gray-300">{type}</span>
              <span className="text-[9px] text-gray-500">({cnt})</span>
            </div>
          ))}
        </div>

        {/* Refresh button */}
        <button
          onClick={() => fetchGraph(graphFilter)}
          className="ml-auto bg-gray-900/90 backdrop-blur rounded-xl px-2.5 py-1.5 text-gray-400 hover:text-gray-200 border border-gray-800 transition"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${graphLoading ? 'animate-spin' : ''}`} />
        </button>
      </div>

      {/* Zoom controls */}
      <div className="absolute right-3 bottom-16 z-10 flex flex-col gap-1">
        <button onClick={() => zoom(1.2)} className="w-8 h-8 bg-gray-900/90 backdrop-blur rounded-lg text-gray-300 hover:text-white border border-gray-800 flex items-center justify-center transition">
          <ZoomIn className="h-3.5 w-3.5" />
        </button>
        <button onClick={() => zoom(0.8)} className="w-8 h-8 bg-gray-900/90 backdrop-blur rounded-lg text-gray-300 hover:text-white border border-gray-800 flex items-center justify-center transition">
          <ZoomOut className="h-3.5 w-3.5" />
        </button>
        <button onClick={resetView} className="w-8 h-8 bg-gray-900/90 backdrop-blur rounded-lg text-gray-300 hover:text-white border border-gray-800 flex items-center justify-center transition text-[10px] font-bold">
          1:1
        </button>
      </div>

      {/* Canvas */}
      <canvas
        ref={canvasRef}
        className="flex-1 w-full h-full"
        onMouseDown={onMouseDown}
        onMouseMove={onMouseMove}
        onMouseUp={onMouseUp}
        onMouseLeave={() => { dragRef.current = null; panRef.current = null; setTooltip(null) }}
        onWheel={onWheel}
      />

      {/* Tooltip */}
      {tooltip && (
        <div
          className="absolute z-20 pointer-events-none bg-gray-900 text-white text-xs rounded-xl px-3 py-2 border border-gray-700 shadow-xl max-w-xs"
          style={{ left: tooltip.x, top: tooltip.y }}
        >
          <div className="flex items-center gap-1.5 mb-1">
            <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: NODE_COLORS[tooltip.node.nodeType] || '#6b7280' }} />
            <span className="font-bold text-white">{tooltip.node.name}</span>
          </div>
          <div className="text-gray-400 text-[10px]">Type: {tooltip.node.nodeType}</div>
          <div className="text-gray-400 text-[10px]">Layer: {tooltip.node.layer}</div>
          {tooltip.node.description && (
            <div className="text-gray-300 text-[10px] mt-1 line-clamp-2">{tooltip.node.description}</div>
          )}
          <div className="text-indigo-400 text-[9px] mt-1">Click to select</div>
        </div>
      )}

      {/* Empty state */}
      {!graphLoading && nodeCount === 0 && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="text-center">
            <Layers className="h-12 w-12 text-gray-700 mx-auto mb-3" />
            <p className="text-gray-500 text-sm font-medium">System graph is empty</p>
            <p className="text-gray-600 text-xs mt-1">Use "Refresh" buttons to ingest code, docs, or Jira</p>
          </div>
        </div>
      )}
    </div>
  )
}
