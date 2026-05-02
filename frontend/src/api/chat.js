import client from './client'

export const sendMessage = (data) => client.post('/chat', data)

export const getConversations = (page = 0, size = 20) =>
  client.get(`/chat/conversations?page=${page}&size=${size}`)

export const getConversation = (id) => client.get(`/chat/conversations/${id}`)

export const deleteConversation = (id) => client.delete(`/chat/conversations/${id}`)

export const submitFeedback = (data) => client.post('/feedback', data)

/**
 * Streaming chat via SSE — returns an EventSource-compatible fetch stream.
 * Calls the /api/chat/stream endpoint and yields chunks to the onChunk callback.
 */
export async function sendMessageStream({ message, conversationId, connectorIds }, onChunk, onDone, onError, onArtifacts) {
  const token = localStorage.getItem('auth_token')
  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        Accept: 'text/event-stream',
      },
      body: JSON.stringify({ message, conversationId, connectorIds, stream: true }),
    })

    if (!response.ok) {
      const err = await response.json().catch(() => ({ message: 'Stream error' }))
      onError(err.message || 'Failed to stream response')
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) { onDone(); break }

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() // keep incomplete last line in buffer

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const text = line.slice(5) // strip 'data:' prefix
          if (text === '[DONE]') { onDone(); return }
          if (text.startsWith('[ARTIFACTS]')) {
            try { onArtifacts?.(JSON.parse(text.slice(11))) } catch {}
            continue
          }
          onChunk(text)
        }
      }
    }
  } catch (err) {
    onError(err.message)
  }
}
