import { useState, useEffect, useCallback, useRef } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import {
  X, Trash2, Settings, RefreshCw, Lock, CheckCircle2, XCircle,
  AlertTriangle, Activity, FileText, Zap, Clock, File
} from 'lucide-react'
import client from '../../api/client'
import { CONNECTOR_META } from './connectorMeta'
import toast from 'react-hot-toast'
import { format, formatDistanceToNow } from 'date-fns'

const JOB_STATUS_META = {
  PENDING:   { cls: 'bg-yellow-100 text-yellow-700',              label: 'Pending' },
  RUNNING:   { cls: 'bg-blue-100 text-blue-700 animate-pulse',    label: 'Running' },
  COMPLETED: { cls: 'bg-green-100 text-green-700',                label: 'Completed' },
  FAILED:    { cls: 'bg-red-100 text-red-700',                    label: 'Failed' },
  CANCELLED: { cls: 'bg-gray-100 text-gray-500',                  label: 'Cancelled' },
}

const DOC_STATUS_META = {
  INGESTING: { cls: 'bg-blue-100 text-blue-700 animate-pulse', label: 'Ingesting' },
  COMPLETED: { cls: 'bg-green-100 text-green-700',             label: 'Indexed' },
  FAILED:    { cls: 'bg-red-100 text-red-700',                 label: 'Failed' },
}

const FILE_TYPE_COLORS = {
  PDF:  'bg-red-100 text-red-700',
  DOCX: 'bg-blue-100 text-blue-700',
  DOC:  'bg-blue-100 text-blue-700',
  XLSX: 'bg-green-100 text-green-700',
  XLS:  'bg-green-100 text-green-700',
  PPTX: 'bg-orange-100 text-orange-700',
  PPT:  'bg-orange-100 text-orange-700',
  TXT:  'bg-gray-100 text-gray-600',
  MD:   'bg-purple-100 text-purple-700',
  CSV:  'bg-teal-100 text-teal-700',
}

function StatusBadge({ status, meta }) {
  const s = meta[status] ?? { cls: 'bg-gray-100 text-gray-500', label: status }
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-semibold ${s.cls}`}>
      {s.label}
    </span>
  )
}

function FileTypeBadge({ type }) {
  const cls = FILE_TYPE_COLORS[type?.toUpperCase()] ?? 'bg-gray-100 text-gray-500'
  return (
    <span className={`inline-flex items-center rounded px-1.5 py-0.5 text-[9px] font-bold tracking-wide ${cls}`}>
      {type ?? '?'}
    </span>
  )
}

function formatBytes(bytes) {
  if (!bytes) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export default function DataSourceDetailModal({ connector, onClose, onEdit, onDeleted }) {
  const [jobs, setJobs] = useState([])
  const [documents, setDocuments] = useState([])
  const [loadingJobs, setLoadingJobs] = useState(false)
  const [loadingDocs, setLoadingDocs] = useState(false)
  const [testing, setTesting] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [syncing, setSyncing] = useState(false)
  const [deletingDocId, setDeletingDocId] = useState(null)
  const fileInputRef = useRef(null)

  const meta = CONNECTOR_META[connector.connectorType] ?? { label: connector.connectorType, bg: 'bg-gray-100' }
  const Icon = meta.Icon
  const isDocuments = connector.connectorType === 'DOCUMENTS'

  const loadJobs = useCallback(async () => {
    setLoadingJobs(true)
    try {
      const { data } = await client.get(`/ingestion/jobs?connectorId=${connector.id}`)
      setJobs(data)
    } catch { /* silent */ }
    finally { setLoadingJobs(false) }
  }, [connector.id])

  const loadDocuments = useCallback(async () => {
    if (!isDocuments) return
    setLoadingDocs(true)
    try {
      const { data } = await client.get(`/ingestion/documents?connectorId=${connector.id}`)
      setDocuments(data)
    } catch { /* silent */ }
    finally { setLoadingDocs(false) }
  }, [connector.id, isDocuments])

  useEffect(() => {
    if (isDocuments) {
      loadDocuments()
    } else {
      loadJobs()
    }
  }, [isDocuments, loadDocuments, loadJobs])

  const handleTest = async () => {
    setTesting(true)
    try {
      const { data } = await client.post(`/connectors/${connector.id}/health`)
      toast[data.healthy ? 'success' : 'error'](
        data.healthy ? `Connected! (${data.latencyMs}ms)` : `Failed: ${data.message}`
      )
    } catch { toast.error('Health check failed') }
    finally { setTesting(false) }
  }

  const handleSyncNow = async () => {
    setSyncing(true)
    try {
      await client.post(`/connectors/${connector.id}/sync`)
      toast.success('Sync started — check back in a moment')
      setTimeout(() => loadJobs(), 2000)
      setTimeout(() => loadJobs(), 8000)
    } catch { toast.error('Failed to start sync') }
    finally { setSyncing(false) }
  }

  const handleDelete = async () => {
    if (connector.readOnly && !isDocuments) { toast.error('Cannot delete a Read Only data source'); return }
    if (!window.confirm(`Remove "${connector.name}"? This cannot be undone.`)) return
    setDeleting(true)
    try {
      await client.delete(`/connectors/${connector.id}`)
      toast.success('Data source removed')
      onDeleted()
    } catch { toast.error('Remove failed') }
    finally { setDeleting(false) }
  }

  const handleDocumentUpload = async (event) => {
    const file = event.target.files?.[0]
    if (!file) return
    setUploading(true)
    try {
      const formData = new FormData()
      formData.append('file', file)
      formData.append('connectorId', connector.id)
      await client.post('/ingestion/upload', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      toast.success(`"${file.name}" uploaded and queued for ingestion`)
      // Poll briefly for the new document to appear
      setTimeout(() => loadDocuments(), 800)
      setTimeout(() => loadDocuments(), 3000)
    } catch {
      toast.error('Document upload failed')
    } finally {
      setUploading(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  const handleDeleteDocument = async (doc) => {
    if (!window.confirm(`Remove "${doc.fileName}" and all its indexed data from the knowledge base?`)) return
    setDeletingDocId(doc.id)
    try {
      await client.delete(`/ingestion/documents/${doc.id}`)
      toast.success(`"${doc.fileName}" removed from knowledge base`)
      setDocuments(prev => prev.filter(d => d.id !== doc.id))
    } catch {
      toast.error('Failed to remove document')
    } finally {
      setDeletingDocId(null)
    }
  }

  const completedJobs = jobs.filter(j => j.status === 'COMPLETED')
  const isGitHub = connector.connectorType === 'GITHUB'
  const totalChunks = isDocuments
    ? documents.filter(d => d.status === 'COMPLETED').reduce((s, d) => s + (d.chunksCount ?? 0), 0)
    : completedJobs.reduce((sum, j) => sum + (j.chunksProcessed ?? 0), 0)
  const failedCount = isDocuments
    ? documents.filter(d => d.status === 'FAILED').length
    : jobs.filter(j => j.status === 'FAILED').length

  return (
    <AnimatePresence>
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
        <motion.div
          initial={{ opacity: 0, scale: 0.95, y: 10 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          exit={{ opacity: 0, scale: 0.95, y: 10 }}
          className="w-full max-w-2xl rounded-2xl bg-white shadow-2xl overflow-hidden flex flex-col max-h-[90vh]"
        >
          {/* Header */}
          <div className="flex items-start gap-4 px-6 py-5 border-b border-gray-100">
            <div className={`w-12 h-12 rounded-2xl flex items-center justify-center flex-shrink-0 ${meta.bg}`}>
              {Icon ? <Icon size={26} /> : <span className="text-xl">🔌</span>}
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <h2 className="text-base font-bold text-gray-900">{connector.name}</h2>
                {connector.readOnly && !isDocuments && (
                  <span className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-semibold text-amber-700">
                    <Lock className="h-2.5 w-2.5" /> Read Only
                  </span>
                )}
                {connector.verified ? (
                  <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-[10px] font-semibold text-green-700">
                    <CheckCircle2 className="h-2.5 w-2.5" /> Connected
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2 py-0.5 text-[10px] font-semibold text-gray-500">
                    <XCircle className="h-2.5 w-2.5" /> Not Verified
                  </span>
                )}
              </div>
              <p className="text-xs text-gray-400 mt-0.5">{meta.label}</p>
              {connector.lastSyncAt && (
                <div className="flex items-center gap-1 mt-1">
                  <Clock className="h-3 w-3 text-gray-300" />
                  <span className="text-[10px] text-gray-400">
                    Last synced {formatDistanceToNow(new Date(connector.lastSyncAt), { addSuffix: true })}
                  </span>
                </div>
              )}
            </div>
            <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 transition flex-shrink-0">
              <X className="h-4 w-4 text-gray-500" />
            </button>
          </div>

          {/* Stats row */}
          <div className="grid grid-cols-3 divide-x divide-gray-100 border-b border-gray-100">
            <div className="py-4 text-center">
              <p className="text-2xl font-bold text-gray-900">
                {isDocuments ? documents.filter(d => d.status === 'COMPLETED').length : completedJobs.length}
              </p>
              <p className="text-[10px] text-gray-400 mt-0.5">{isDocuments ? 'Indexed Docs' : 'Completed Syncs'}</p>
            </div>
            <div className="py-4 text-center">
              <p className="text-2xl font-bold text-gray-900">{totalChunks.toLocaleString()}</p>
              <p className="text-[10px] text-gray-400 mt-0.5">{isGitHub ? 'Items Indexed' : 'Chunks Indexed'}</p>
            </div>
            <div className="py-4 text-center">
              <p className={`text-2xl font-bold ${failedCount > 0 ? 'text-red-600' : 'text-gray-900'}`}>
                {failedCount}
              </p>
              <p className="text-[10px] text-gray-400 mt-0.5">Failed</p>
            </div>
          </div>

          {/* Scrollable body */}
          <div className="flex-1 overflow-y-auto">
            {connector.lastError && (
              <div className="mx-6 mt-4 flex items-start gap-2.5 rounded-xl bg-red-50 border border-red-200 px-4 py-3">
                <AlertTriangle className="h-4 w-4 text-red-500 flex-shrink-0 mt-px" />
                <div>
                  <p className="text-xs font-semibold text-red-700">Last Error</p>
                  <p className="text-xs text-red-600 mt-0.5 break-words">{connector.lastError}</p>
                </div>
              </div>
            )}

            <div className="px-6 py-4">
              {isDocuments ? (
                /* ── Documents view ── */
                <>
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <FileText className="h-4 w-4 text-gray-400" />
                      <h3 className="text-sm font-semibold text-gray-700">Uploaded Documents</h3>
                      {documents.length > 0 && (
                        <span className="text-[10px] text-gray-400">· {documents.length} file{documents.length !== 1 ? 's' : ''}</span>
                      )}
                    </div>
                    <button
                      onClick={loadDocuments}
                      title="Refresh"
                      className="p-1 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition"
                    >
                      <RefreshCw className={`h-3.5 w-3.5 ${loadingDocs ? 'animate-spin' : ''}`} />
                    </button>
                  </div>

                  {loadingDocs && documents.length === 0 ? (
                    <div className="py-10 text-center">
                      <RefreshCw className="h-6 w-6 mx-auto mb-2 text-gray-300 animate-spin" />
                      <p className="text-xs text-gray-400">Loading documents…</p>
                    </div>
                  ) : documents.length === 0 ? (
                    <div className="py-10 text-center">
                      <File className="h-8 w-8 mx-auto mb-2 text-gray-200" />
                      <p className="text-xs text-gray-400">No documents uploaded yet</p>
                      <p className="text-[10px] text-gray-300 mt-1">Use "Browse Files" below to add documents</p>
                    </div>
                  ) : (
                    <div className="space-y-2">
                      {documents.map(doc => (
                        <div
                          key={doc.id}
                          className="flex items-center gap-3 rounded-xl border border-gray-100 bg-gray-50 px-3 py-2.5"
                        >
                          <div className="flex-shrink-0">
                            <FileTypeBadge type={doc.fileType} />
                          </div>
                          <div className="flex-1 min-w-0">
                            <p className="text-xs font-medium text-gray-700 truncate" title={doc.fileName}>
                              {doc.fileName}
                            </p>
                            <p className="text-[10px] text-gray-400 mt-0.5">
                              {doc.uploadedAt ? format(new Date(doc.uploadedAt), 'MMM d, HH:mm') : '—'}
                              {doc.fileSize && <span className="ml-1.5">· {formatBytes(doc.fileSize)}</span>}
                              {doc.chunksCount != null && doc.status === 'COMPLETED' && (
                                <span className="ml-1.5">· {doc.chunksCount} chunks</span>
                              )}
                            </p>
                            {doc.status === 'FAILED' && doc.errorMessage && (
                              <p className="text-[10px] text-red-500 mt-0.5 truncate" title={doc.errorMessage}>
                                {doc.errorMessage}
                              </p>
                            )}
                          </div>
                          <StatusBadge status={doc.status} meta={DOC_STATUS_META} />
                          <button
                            onClick={() => handleDeleteDocument(doc)}
                            disabled={deletingDocId === doc.id}
                            title="Remove from knowledge base"
                            className="flex-shrink-0 p-1.5 rounded-lg text-gray-400 hover:text-red-500 hover:bg-red-50 transition disabled:opacity-40"
                          >
                            {deletingDocId === doc.id
                              ? <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                              : <Trash2 className="h-3.5 w-3.5" />
                            }
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </>
              ) : (
                /* ── Ingestion jobs view (non-document connectors) ── */
                <>
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <Activity className="h-4 w-4 text-gray-400" />
                      <h3 className="text-sm font-semibold text-gray-700">Ingestion History</h3>
                      <span className="text-[10px] text-gray-400">· {meta.label} jobs</span>
                    </div>
                    <button
                      onClick={loadJobs}
                      title="Refresh"
                      className="p-1 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition"
                    >
                      <RefreshCw className={`h-3.5 w-3.5 ${loadingJobs ? 'animate-spin' : ''}`} />
                    </button>
                  </div>

                  {jobs.length === 0 ? (
                    <div className="py-10 text-center">
                      <FileText className="h-8 w-8 mx-auto mb-2 text-gray-200" />
                      <p className="text-xs text-gray-400">No ingestion jobs recorded for this source type yet</p>
                    </div>
                  ) : (
                    <div className="space-y-2">
                      {jobs.slice(0, 12).map(job => (
                        <div
                          key={job.id}
                          className="flex items-center gap-3 rounded-xl border border-gray-100 bg-gray-50 px-3 py-2.5"
                        >
                          <div className="flex-1 min-w-0">
                            <p className="text-xs font-medium text-gray-700 truncate">
                              {job.sourceRef || 'Batch ingestion'}
                            </p>
                            <p className="text-[10px] text-gray-400 mt-0.5">
                              {job.createdAt ? format(new Date(job.createdAt), 'MMM d, HH:mm') : '—'}
                              {job.chunksProcessed != null && (
                                <span className="ml-1.5">· {job.chunksProcessed}/{job.chunksTotal ?? '?'} chunks</span>
                              )}
                            </p>
                          </div>
                          <StatusBadge status={job.status} meta={JOB_STATUS_META} />
                        </div>
                      ))}
                      {jobs.length > 12 && (
                        <p className="text-[10px] text-center text-gray-400 pt-1">
                          +{jobs.length - 12} more jobs not shown
                        </p>
                      )}
                    </div>
                  )}
                </>
              )}
            </div>
          </div>

          {/* Footer actions */}
          <div className="flex items-center gap-2 px-6 py-4 border-t border-gray-100 bg-gray-50">
            <button
              onClick={handleTest}
              disabled={testing}
              className="flex items-center gap-1.5 rounded-xl border border-gray-200 bg-white px-3.5 py-2 text-xs font-medium text-gray-700 hover:bg-gray-50 transition"
            >
              <Zap className="h-3.5 w-3.5 text-blue-500" />
              {testing ? 'Testing…' : 'Test Connection'}
            </button>
            {!isDocuments && isGitHub && (
              <button
                onClick={handleSyncNow}
                disabled={syncing}
                className="flex items-center gap-1.5 rounded-xl border border-indigo-200 bg-indigo-50 px-3.5 py-2 text-xs font-medium text-indigo-700 hover:bg-indigo-100 transition disabled:opacity-40 disabled:cursor-not-allowed"
              >
                <RefreshCw className={`h-3.5 w-3.5 ${syncing ? 'animate-spin' : ''}`} />
                {syncing ? 'Syncing…' : 'Sync Now'}
              </button>
            )}
            <button
              onClick={() => onEdit(connector)}
              className="flex items-center gap-1.5 rounded-xl border border-gray-200 bg-white px-3.5 py-2 text-xs font-medium text-gray-700 hover:bg-gray-50 transition"
            >
              <Settings className="h-3.5 w-3.5 text-gray-500" />
              Edit Config
            </button>
            <div className="flex-1" />
            <button
              onClick={handleDelete}
              disabled={deleting || (connector.readOnly && !isDocuments)}
              title={connector.readOnly && !isDocuments ? 'Cannot remove a Read Only source' : 'Remove this data source'}
              className="flex items-center gap-1.5 rounded-xl border border-red-200 bg-red-50 px-3.5 py-2 text-xs font-medium text-red-600 hover:bg-red-100 transition disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <Trash2 className="h-3.5 w-3.5" />
              {deleting ? 'Removing…' : 'Remove'}
            </button>
            {isDocuments && (
              <>
                <input
                  ref={fileInputRef}
                  type="file"
                  className="hidden"
                  accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md,.csv"
                  onChange={handleDocumentUpload}
                />
                <button
                  onClick={() => fileInputRef.current?.click()}
                  disabled={uploading}
                  className="flex items-center gap-1.5 rounded-xl border border-blue-200 bg-blue-50 px-3.5 py-2 text-xs font-medium text-blue-700 hover:bg-blue-100 transition disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <FileText className="h-3.5 w-3.5" />
                  {uploading ? 'Uploading…' : 'Browse Files'}
                </button>
              </>
            )}
          </div>
        </motion.div>
      </div>
    </AnimatePresence>
  )
}
