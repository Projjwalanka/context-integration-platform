import { useEffect, useRef, useState, useCallback } from 'react'
import { useChatStore } from '../../store/chatStore'
import { sendMessage, sendMessageStream, submitFeedback } from '../../api/chat'
import MessageBubble from './MessageBubble'
import ChatInput from './ChatInput'
import { motion, AnimatePresence } from 'framer-motion'
import { Bot } from 'lucide-react'
import toast from 'react-hot-toast'

export default function ChatInterface() {
  const {
    messages, currentConversationId, isStreaming, streamingContent, activeConnectors,
    addMessage, setStreaming, appendStreamChunk, finalizeStream, setCurrentConversation
  } = useChatStore()

  const messagesEndRef = useRef(null)
  const [pendingFeedback, setPendingFeedback] = useState(null)

  // Auto-scroll to bottom
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, streamingContent])

  const handleSend = useCallback(async (text) => {
    if (!text.trim() || isStreaming) return

    const userMessage = { id: Date.now().toString(), role: 'USER', content: text, createdAt: new Date().toISOString() }
    addMessage(userMessage)
    setStreaming(true)

    const request = {
      message: text,
      conversationId: currentConversationId,
      connectorIds: activeConnectors,
      stream: true,
    }

    let fullContent = ''
    let streamedArtifacts = []

    sendMessageStream(
      request,
      (chunk) => {
        fullContent += chunk
        appendStreamChunk(chunk)
      },
      () => {
        const assistantMessage = {
          id: Date.now().toString() + '_ai',
          role: 'ASSISTANT',
          content: fullContent,
          artifacts: streamedArtifacts,
          createdAt: new Date().toISOString(),
        }
        finalizeStream(assistantMessage)
      },
      (err) => {
        setStreaming(false)
        toast.error('Failed to get response: ' + err)
      },
      (artifacts) => {
        streamedArtifacts = artifacts
      }
    )
  }, [isStreaming, currentConversationId, activeConnectors, addMessage, setStreaming, appendStreamChunk, finalizeStream])

  const handleFeedback = async (messageId, type) => {
    try {
      await submitFeedback({
        messageId,
        conversationId: currentConversationId || 'unknown',
        type,
      })
      toast.success(type === 'THUMBS_UP' ? 'Thanks for the positive feedback!' : 'Thanks for your feedback — we\'ll improve!')
    } catch {
      toast.error('Failed to submit feedback')
    }
  }

  const isEmpty = messages.length === 0 && !isStreaming

  return (
    <div className="flex flex-col h-full">
      {/* Messages area */}
      <div className="flex-1 overflow-y-auto px-4 py-6 space-y-6">
        {isEmpty && (
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            className="flex flex-col items-center justify-center h-full text-center py-16"
          >
            <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-blue-100 mb-4">
              <Bot className="h-9 w-9 text-blue-600" />
            </div>
            <h2 className="text-xl font-semibold text-gray-800 mb-2">How can I help you today?</h2>
            <p className="text-sm text-gray-500 max-w-sm">
              Ask me anything about your system. I can also generate responses in Word, Excel, PDF, XML, JSON and more.
            </p>
            {/* Example prompts */}
            <div className="grid grid-cols-2 gap-3 mt-8 max-w-lg w-full">
              {[
                'Summarize what this system does',
                'Tell me about latest commits',
                'Summarize open Jira tickets for this sprint',
                'Create an excel with all repository details',
              ].map((prompt) => (
                <button
                  key={prompt}
                  onClick={() => handleSend(prompt)}
                  className="rounded-xl border border-gray-200 bg-white p-3 text-left text-xs text-gray-600
                             shadow-sm transition hover:border-blue-300 hover:shadow-md"
                >
                  {prompt}
                </button>
              ))}
            </div>
          </motion.div>
        )}

        <AnimatePresence initial={false}>
          {messages.map((msg) => (
            <motion.div
              key={msg.id}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.2 }}
            >
              <MessageBubble
                message={msg}
                onFeedback={msg.role === 'ASSISTANT' ? handleFeedback : null}
              />
            </motion.div>
          ))}
        </AnimatePresence>

        {/* Streaming message */}
        {isStreaming && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
            <MessageBubble
              message={{ id: 'streaming', role: 'ASSISTANT', content: streamingContent, streaming: true }}
            />
          </motion.div>
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Input area */}
      <div className="border-t border-gray-200 bg-white px-4 py-3">
        <ChatInput onSend={handleSend} disabled={isStreaming} />
      </div>
    </div>
  )
}
