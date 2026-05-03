import client from './client'

// NOTE: axios client has baseURL '/api' — so paths here MUST NOT include the
// '/api' prefix (otherwise requests go to /api/api/... and return 404).

// ─── System Graph ────────────────────────────────────────────────────────────

export const fetchSystemGraph = (params = {}) =>
  client.get('/graph/system', { params }).then(r => r.data)

export const fetchGraphNode = (id, hops = 1) =>
  client.get(`/graph/node/${id}`, { params: { hops } }).then(r => r.data)

export const searchGraphNodes = (q, nodeType) =>
  client.get('/graph/search', { params: { q, ...(nodeType ? { nodeType } : {}) } }).then(r => r.data)

export const fetchGraphStats = () =>
  client.get('/graph/stats').then(r => r.data)

// ─── Node Management ─────────────────────────────────────────────────────────

export const upsertGraphNode = (node) =>
  client.post('/graph/node', node).then(r => r.data)

export const createGraphEdge = (edge) =>
  client.post('/graph/edge', edge).then(r => r.data)

export const deleteGraphNode = (id) =>
  client.delete(`/graph/node/${id}`).then(r => r.data)

// ─── Ingestion Refresh ───────────────────────────────────────────────────────

export const refreshCode = (connectorId) =>
  client.post('/graph/refresh/code', null, {
    params: connectorId ? { connectorId } : {},
  }).then(r => r.data)

export const refreshDocs = (connectorId) =>
  client.post('/graph/refresh/docs', null, {
    params: connectorId ? { connectorId } : {},
  }).then(r => r.data)

export const refreshJira = (connectorId) =>
  client.post('/graph/refresh/jira', null, {
    params: connectorId ? { connectorId } : {},
  }).then(r => r.data)

export const refreshAllSources = () =>
  client.post('/graph/refresh/all').then(r => r.data)

export const fetchRefreshStatus = () =>
  client.get('/graph/refresh/status').then(r => r.data)

// ─── Impact Analysis ─────────────────────────────────────────────────────────

export const analyzeImpact = (nodeId) =>
  client.post(`/graph/impact/${nodeId}`).then(r => r.data)

// ─── Context Engineering ─────────────────────────────────────────────────────

export const buildContext = (query, maxDepth = 3) =>
  client.post('/context/build', {
    query,
    maxDepth,
    includeCode: true,
    includeDocs: true,
    includeJira: true,
  }).then(r => r.data)

export const fetchExplanation = (queryId) =>
  client.get(`/explain/${queryId}`).then(r => r.data)

// ─── Clear All Knowledge Graph Data ──────────────────────────────────────────

export const clearAllKgData = () =>
  client.delete('/kg/clear-all').then(r => r.data)

// ─── GitHub Repository Browser ────────────────────────────────────────────────

export const fetchGithubRepos = (connectorId) =>
  client.get(`/connectors/${connectorId}/github/repos`).then(r => r.data)
