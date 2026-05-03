import { useState, useRef, useEffect } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { X, Eye, EyeOff, TestTube2, Save, ChevronLeft, Shield, Lock, ExternalLink, CheckCircle2, AlertCircle, RefreshCw, GitBranch, Check, ChevronDown } from 'lucide-react'
import client from '../../api/client'
import toast from 'react-hot-toast'
import { CONNECTOR_META } from './connectorMeta'

const CREDENTIAL_FIELDS = {
  JIRA: [
    { key: 'baseUrl',   label: 'Base URL',   placeholder: 'https://acme.atlassian.net' },
    { key: 'email',     label: 'Email' },
    { key: 'apiToken',  label: 'API Token',  secret: true },
  ],
  CONFLUENCE: [
    { key: 'baseUrl',   label: 'Base URL',   placeholder: 'https://acme.atlassian.net' },
    { key: 'email',     label: 'Email' },
    { key: 'apiToken',  label: 'API Token',  secret: true },
  ],
  GITHUB: [
    { key: 'personalAccessToken', label: 'Personal Access Token', secret: true },
    { key: 'org',                 label: 'Organisation (optional)' },
  ],
  SHAREPOINT: [
    { key: 'tenantId',     label: 'Tenant ID' },
    { key: 'clientId',     label: 'Client ID' },
    { key: 'clientSecret', label: 'Client Secret', secret: true },
  ],
  DOCUMENTS: [],
}

// ── Step 1: Type Picker ────────────────────────────────────────────────────────

function TypeSelector({ onSelect }) {
  return (
    <div className="p-6">
      <p className="text-xs text-gray-400 mb-4">Select a data source type to connect</p>
      <div className="grid grid-cols-2 gap-3">
        {Object.entries(CONNECTOR_META).map(([type, meta]) => {
          const Icon = meta.Icon
          return (
            <button
              key={type}
              onClick={() => onSelect(type)}
              className="group flex flex-col items-center gap-2.5 rounded-xl border border-gray-100 p-4
                         hover:border-blue-200 hover:bg-blue-50 hover:shadow-sm transition text-center"
            >
              <div className={`w-11 h-11 rounded-xl flex items-center justify-center ${meta.bg} group-hover:scale-105 transition-transform`}>
                {Icon && <Icon size={24} />}
              </div>
              <div>
                <p className="text-xs font-semibold text-gray-800">{meta.label}</p>
                <p className="text-[10px] text-gray-400 mt-0.5 leading-tight">{meta.description}</p>
              </div>
              {meta.supportsOAuth && (
                <span className="text-[9px] bg-blue-100 text-blue-600 rounded-full px-1.5 py-0.5 font-semibold">
                  OAuth
                </span>
              )}
            </button>
          )
        })}
      </div>
    </div>
  )
}

// ── GitHub Repo/Branch Selector ────────────────────────────────────────────────

function GitHubRepoSelector({ connectorId, initialSelected, onChange }) {
  const [repos, setRepos] = useState([])
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState(initialSelected ?? []) // [{fullName, branch}]
  const [openBranchDropdown, setOpenBranchDropdown] = useState(null)

  const loadRepos = async () => {
    setLoading(true)
    try {
      const { data } = await client.get(`/connectors/${connectorId}/github/repos`)
      setRepos(data)
    } catch {
      toast.error('Failed to load repositories — check your credentials')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { loadRepos() }, [connectorId])

  const isSelected = (fullName) => selected.some(s => s.fullName === fullName)

  const toggleRepo = (repo) => {
    const next = isSelected(repo.fullName)
      ? selected.filter(s => s.fullName !== repo.fullName)
      : [...selected, { fullName: repo.fullName, branch: repo.defaultBranch }]
    setSelected(next)
    onChange(next)
  }

  const setBranch = (fullName, branch) => {
    const next = selected.map(s => s.fullName === fullName ? { ...s, branch } : s)
    setSelected(next)
    onChange(next)
    setOpenBranchDropdown(null)
  }

  const selectedBranchFor = (fullName) =>
    selected.find(s => s.fullName === fullName)?.branch ?? ''

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <label className="text-xs font-semibold text-gray-700 flex items-center gap-1.5">
          <GitBranch className="h-3.5 w-3.5 text-indigo-500" />
          Select Repositories &amp; Branches
        </label>
        <button
          type="button"
          onClick={loadRepos}
          disabled={loading}
          className="text-[10px] text-indigo-600 hover:text-indigo-800 flex items-center gap-1 disabled:opacity-50"
        >
          <RefreshCw className={`h-2.5 w-2.5 ${loading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>

      {selected.length > 0 && (
        <p className="text-[10px] text-gray-500">
          {selected.length} repo{selected.length !== 1 ? 's' : ''} selected — only these will be ingested.
          Deselect all to ingest all accessible repos.
        </p>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-6 gap-2 text-xs text-gray-400">
          <RefreshCw className="h-4 w-4 animate-spin" />
          Loading repositories…
        </div>
      ) : repos.length === 0 ? (
        <div className="rounded-xl border border-dashed border-gray-200 py-6 text-center text-xs text-gray-400">
          No repositories found. Verify your access token has the required scopes.
        </div>
      ) : (
        <div className="max-h-56 overflow-y-auto rounded-xl border border-gray-200 divide-y divide-gray-100">
          {repos.map(repo => {
            const sel = isSelected(repo.fullName)
            return (
              <div key={repo.fullName} className={`flex items-center gap-2 px-3 py-2 transition ${sel ? 'bg-indigo-50' : 'hover:bg-gray-50'}`}>
                <button
                  type="button"
                  onClick={() => toggleRepo(repo)}
                  className={`w-4 h-4 rounded flex-shrink-0 border-2 flex items-center justify-center transition ${
                    sel ? 'bg-indigo-600 border-indigo-600' : 'border-gray-300 hover:border-indigo-400'
                  }`}
                >
                  {sel && <Check className="h-2.5 w-2.5 text-white" strokeWidth={3} />}
                </button>
                <div className="flex-1 min-w-0">
                  <p className="text-xs font-medium text-gray-800 truncate">{repo.fullName}</p>
                  {repo.description && (
                    <p className="text-[9px] text-gray-400 truncate">{repo.description}</p>
                  )}
                </div>
                {sel && repo.branches && repo.branches.length > 0 && (
                  <div className="relative flex-shrink-0">
                    <button
                      type="button"
                      onClick={() => setOpenBranchDropdown(
                        openBranchDropdown === repo.fullName ? null : repo.fullName
                      )}
                      className="flex items-center gap-1 text-[10px] rounded-lg border border-indigo-200 bg-white px-2 py-0.5 text-indigo-700 hover:bg-indigo-50 transition"
                    >
                      <GitBranch className="h-2.5 w-2.5" />
                      {selectedBranchFor(repo.fullName) || repo.defaultBranch}
                      <ChevronDown className="h-2.5 w-2.5" />
                    </button>
                    {openBranchDropdown === repo.fullName && (
                      <div className="absolute right-0 top-full mt-1 z-30 w-36 rounded-xl border border-gray-200 bg-white shadow-lg py-1 max-h-40 overflow-y-auto">
                        {repo.branches.map(b => (
                          <button
                            key={b}
                            type="button"
                            onClick={() => setBranch(repo.fullName, b)}
                            className={`w-full text-left px-3 py-1.5 text-xs hover:bg-indigo-50 transition ${
                              selectedBranchFor(repo.fullName) === b ? 'text-indigo-700 font-semibold' : 'text-gray-700'
                            }`}
                          >
                            {b}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                )}
                {!sel && (
                  <span className="text-[9px] text-gray-400 flex-shrink-0">{repo.defaultBranch}</span>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

// ── Step 2: Configuration Form ─────────────────────────────────────────────────

function ConfigForm({ type, connector, onSaved }) {
  const isEdit = !!connector
  const isDocuments = type === 'DOCUMENTS'
  const meta = CONNECTOR_META[type] ?? {}
  const Icon = meta.Icon
  const fields = CREDENTIAL_FIELDS[type] ?? []

  const [name, setName] = useState(connector?.name ?? '')
  const [readOnly, setReadOnly] = useState(connector?.readOnly ?? false)
  const [useManual, setUseManual] = useState(!meta.supportsOAuth || isEdit)
  const [credentials, setCredentials] = useState({})
  const [showSecrets, setShowSecrets] = useState({})
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [oauthStatus, setOauthStatus] = useState(null) // null | 'pending' | 'connected' | 'error'
  const pollRef = useRef(null)
  // GitHub-specific: selected repos/branches stored in connector config
  const [selectedRepos, setSelectedRepos] = useState(
    connector?.config?.selectedRepos ?? []
  )

  const initiateOAuth = async () => {
    if (!name.trim()) { toast.error('Enter a display name first'); return }
    setOauthStatus('pending')
    try {
      const params = new URLSearchParams({ type, name: name.trim(), readOnly: String(isDocuments ? false : readOnly) })
      const { data } = await client.get(`/connectors/oauth/initiate?${params}`)

      // Backend returns {error, message} when provider credentials are not configured
      if (data.error) {
        setOauthStatus(null)
        setUseManual(true)
        toast.error(data.message, { duration: 6000 })
        return
      }

      const popup = window.open(data.authUrl, 'oauth_connect', 'width=620,height=720,scrollbars=yes,resizable=yes')
      if (!popup) {
        toast.error('Popup blocked — allow popups for this site and try again')
        setOauthStatus(null)
        return
      }

      const handler = (event) => {
        if (event.data?.type === 'OAUTH_SUCCESS') {
          window.removeEventListener('message', handler)
          clearInterval(pollRef.current)
          setOauthStatus('connected')
          toast.success(`Connected via ${meta.oauthProvider} OAuth!`)
          setTimeout(() => onSaved(), 1200)
        } else if (event.data?.type === 'OAUTH_ERROR') {
          window.removeEventListener('message', handler)
          clearInterval(pollRef.current)
          setOauthStatus('error')
          toast.error('OAuth connection failed — try again or use manual credentials')
        }
      }
      window.addEventListener('message', handler)

      pollRef.current = setInterval(() => {
        if (popup?.closed) {
          clearInterval(pollRef.current)
          window.removeEventListener('message', handler)
          setOauthStatus(s => s === 'pending' ? null : s)
        }
      }, 800)
    } catch {
      setOauthStatus(null)
      toast.error('Failed to initiate OAuth — check server connectivity')
    }
  }

  const handleSave = async (e) => {
    e.preventDefault()
    if (!name.trim()) { toast.error('Display name is required'); return }
    setSaving(true)
    try {
      const configPayload = type === 'GITHUB' && selectedRepos.length > 0
        ? { selectedRepos }
        : undefined
      const payload = {
        connectorType: type,
        name: name.trim(),
        credentials,
        config: configPayload,
        enabled: true,
        readOnly: isDocuments ? false : readOnly,
      }
      if (isEdit) {
        await client.put(`/connectors/${connector.id}`, payload)
        toast.success('Connector updated')
      } else {
        await client.post('/connectors', payload)
        toast.success('Connector created')
      }
      onSaved()
    } catch (err) {
      toast.error(err.response?.data?.message ?? 'Save failed')
    } finally {
      setSaving(false)
    }
  }

  const handleTest = async () => {
    if (!connector?.id) { toast.error('Save the connector first'); return }
    setTesting(true)
    try {
      const { data } = await client.post(`/connectors/${connector.id}/health`)
      toast[data.healthy ? 'success' : 'error'](
        data.healthy ? `Connected! (${data.latencyMs}ms)` : `Failed: ${data.message}`
      )
    } catch { toast.error('Test failed') }
    finally { setTesting(false) }
  }

  return (
    <form onSubmit={handleSave}>
      {/* Source type header */}
      <div className="flex items-center gap-3 px-6 pb-4 border-b border-gray-100">
        <div className={`w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 ${meta.bg}`}>
          {Icon && <Icon size={22} />}
        </div>
        <div>
          <p className="text-sm font-semibold text-gray-800">{meta.label}</p>
          <p className="text-[11px] text-gray-400">{meta.description}</p>
        </div>
      </div>

      <div className="px-6 py-4 space-y-4">
        {/* Display Name */}
        <div>
          <label className="block text-xs font-medium text-gray-700 mb-1.5">
            Display Name <span className="text-red-400">*</span>
          </label>
          <input
            value={name}
            onChange={e => setName(e.target.value)}
            className="input-field"
            placeholder={`e.g. ${meta.label} Production`}
            required
          />
        </div>

        {!isDocuments && (
          <div className="flex items-center justify-between rounded-xl border border-gray-100 bg-gray-50 px-3.5 py-3">
            <div className="flex items-center gap-2.5">
              <Lock className="h-4 w-4 text-amber-500 flex-shrink-0" />
              <div>
                <p className="text-xs font-semibold text-gray-800">Read Only</p>
                <p className="text-[10px] text-gray-400">Block all write operations to this source</p>
              </div>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={readOnly}
              onClick={() => setReadOnly(r => !r)}
              className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors
                ${readOnly ? 'bg-amber-500' : 'bg-gray-200'}`}
            >
              <span className={`pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow transition-transform
                ${readOnly ? 'translate-x-4' : 'translate-x-0'}`} />
            </button>
          </div>
        )}

        {/* OAuth section (for supported types) */}
        {meta.supportsOAuth && !isEdit && (
          <>
            <div className="flex items-center gap-3">
              <div className="flex-1 h-px bg-gray-100" />
              <span className="text-[10px] text-gray-400 font-semibold uppercase tracking-wider">Connection</span>
              <div className="flex-1 h-px bg-gray-100" />
            </div>

            {oauthStatus === 'connected' ? (
              <div className="flex items-center gap-2.5 rounded-xl bg-green-50 border border-green-200 px-4 py-3">
                <CheckCircle2 className="h-4 w-4 text-green-600 flex-shrink-0" />
                <p className="text-xs font-semibold text-green-700">
                  Successfully connected via {meta.oauthProvider} OAuth
                </p>
              </div>
            ) : oauthStatus === 'error' ? (
              <div className="flex items-start gap-2.5 rounded-xl bg-red-50 border border-red-200 px-4 py-3">
                <AlertCircle className="h-4 w-4 text-red-500 flex-shrink-0 mt-px" />
                <div>
                  <p className="text-xs font-semibold text-red-700">OAuth connection failed</p>
                  <p className="text-[11px] text-red-600 mt-0.5">
                    Use manual credentials below, or configure OAuth app credentials in your environment variables.
                  </p>
                </div>
              </div>
            ) : !useManual ? (
              <>
                <button
                  type="button"
                  onClick={initiateOAuth}
                  disabled={oauthStatus === 'pending'}
                  className="w-full flex items-center justify-center gap-2 rounded-xl border-2 border-blue-200 bg-blue-50 px-4 py-3 text-xs font-semibold text-blue-700 hover:bg-blue-100 hover:border-blue-300 transition disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  <Shield className="h-3.5 w-3.5" />
                  {oauthStatus === 'pending' ? 'Connecting…' : `Connect via ${meta.oauthProvider} OAuth`}
                  <ExternalLink className="h-3 w-3 opacity-60" />
                </button>
                <div className="flex items-center gap-3">
                  <div className="flex-1 h-px bg-gray-100" />
                  <button
                    type="button"
                    onClick={() => setUseManual(true)}
                    className="text-[10px] text-gray-400 hover:text-gray-600 transition"
                  >
                    Use API token instead
                  </button>
                  <div className="flex-1 h-px bg-gray-100" />
                </div>
              </>
            ) : null}
          </>
        )}

        {/* Manual credential fields */}
        {(useManual || isEdit || !meta.supportsOAuth || oauthStatus === 'error') &&
          oauthStatus !== 'connected' &&
          fields.map(f => (
            <div key={f.key}>
              <label className="block text-xs font-medium text-gray-700 mb-1.5">{f.label}</label>
              <div className="relative">
                <input
                  type={f.secret && !showSecrets[f.key] ? 'password' : 'text'}
                  value={credentials[f.key] ?? ''}
                  onChange={e => setCredentials(c => ({ ...c, [f.key]: e.target.value }))}
                  placeholder={f.placeholder}
                  className="input-field pr-9"
                />
                {f.secret && (
                  <button
                    type="button"
                    onClick={() => setShowSecrets(s => ({ ...s, [f.key]: !s[f.key] }))}
                    className="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                  >
                    {showSecrets[f.key] ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                )}
              </div>
            </div>
          ))
        }

        {/* GitHub repo/branch selector — shown when editing a saved GitHub connector */}
        {type === 'GITHUB' && isEdit && connector?.id && oauthStatus !== 'connected' && (
          <>
            <div className="flex items-center gap-3">
              <div className="flex-1 h-px bg-gray-100" />
              <span className="text-[10px] text-gray-400 font-semibold uppercase tracking-wider">Repositories</span>
              <div className="flex-1 h-px bg-gray-100" />
            </div>
            <GitHubRepoSelector
              connectorId={connector.id}
              initialSelected={selectedRepos}
              onChange={setSelectedRepos}
            />
          </>
        )}
      </div>

      {/* Action buttons */}
      {oauthStatus !== 'connected' && (useManual || isEdit || !meta.supportsOAuth || oauthStatus === 'error') && (
        <div className="px-6 pb-6 flex gap-2">
          {isEdit && (
            <button
              type="button"
              onClick={handleTest}
              disabled={testing}
              className="btn-secondary flex-1 justify-center"
            >
              <TestTube2 className="h-4 w-4" />
              {testing ? 'Testing…' : 'Test Connection'}
            </button>
          )}
          <button type="submit" disabled={saving} className="btn-primary flex-1 justify-center">
            <Save className="h-4 w-4" />
            {saving ? 'Saving…' : isEdit ? 'Update' : 'Save'}
          </button>
        </div>
      )}
    </form>
  )
}

// ── Modal Wrapper ──────────────────────────────────────────────────────────────

export default function ConnectorConfigModal({ connector, onClose, onSaved }) {
  const isEdit = !!connector
  const [selectedType, setSelectedType] = useState(connector?.connectorType ?? null)

  return (
    <AnimatePresence>
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
        <motion.div
          initial={{ opacity: 0, scale: 0.95, y: 10 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          exit={{ opacity: 0, scale: 0.95, y: 10 }}
          className="w-full max-w-lg rounded-2xl bg-white shadow-2xl overflow-hidden"
        >
          {/* Header */}
          <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
            <div className="flex items-center gap-2">
              {!isEdit && selectedType && (
                <button
                  onClick={() => setSelectedType(null)}
                  className="p-1 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition mr-1"
                >
                  <ChevronLeft className="h-4 w-4" />
                </button>
              )}
              <h2 className="font-semibold text-gray-900">
                {isEdit ? 'Edit Data Source' : selectedType ? 'Configure Source' : 'Add Data Source'}
              </h2>
            </div>
            <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 transition">
              <X className="h-4 w-4 text-gray-500" />
            </button>
          </div>

          {/* Body */}
          <div className="max-h-[82vh] overflow-y-auto">
            {!selectedType ? (
              <TypeSelector onSelect={setSelectedType} />
            ) : (
              <ConfigForm
                type={selectedType}
                connector={connector}
                onSaved={onSaved}
              />
            )}
          </div>
        </motion.div>
      </div>
    </AnimatePresence>
  )
}
