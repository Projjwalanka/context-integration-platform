import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'
import { useChatStore } from '../store/chatStore'
import ChatInterface from '../components/chat/ChatInterface'
import ConnectorPanel from '../components/connectors/ConnectorPanel'
import { getConversations, getConversation } from '../api/chat'
import { motion, AnimatePresence } from 'framer-motion'
import {
  Bot, MessageSquarePlus, LogOut, Settings, ChevronLeft, GitBranch,
  ChevronRight, Trash2, MessageSquare, Database
} from 'lucide-react'
import { format } from 'date-fns'
import toast from 'react-hot-toast'

export default function ChatPage() {
  const { user, logout } = useAuthStore()
  const { conversations, currentConversationId, isSidebarOpen,
          setConversations, setCurrentConversation, setMessages, reset, toggleSidebar } = useChatStore()
  const [showConnectors, setShowConnectors] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    getConversations().then(({ data }) => setConversations(data.content || [])).catch(() => {})
  }, [])

  const handleLogout = async () => {
    await logout()
    navigate('/login')
  }

  const startNewChat = () => reset()

  const handleSelectConversation = async (id) => {
    setCurrentConversation(id)
    try {
      const { data } = await getConversation(id)
      setMessages(data.messages || [])
    } catch {
      toast.error('Failed to load conversation')
    }
  }

  return (
    <div className="flex h-screen overflow-hidden bg-gray-50">

      {/* ── Sidebar ── */}
      <AnimatePresence>
        {isSidebarOpen && (
          <motion.aside
            initial={{ width: 0, opacity: 0 }}
            animate={{ width: 260, opacity: 1 }}
            exit={{ width: 0, opacity: 0 }}
            className="flex flex-col border-r border-gray-200 bg-white overflow-hidden flex-shrink-0"
          >
            {/* Brand */}
            <div className="flex items-center gap-2.5 px-4 py-4 border-b border-gray-100">
              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-600">
                <Bot className="h-4.5 w-4.5 text-white h-5 w-5" />
              </div>
              <div>
                <p className="text-sm font-semibold text-gray-800">Context Integration Platform</p>
                <p className="text-[10px] text-gray-400">US Bank</p>
              </div>
            </div>

            {/* New chat */}
            <div className="px-3 py-3">
              <button onClick={startNewChat}
                className="flex w-full items-center gap-2 rounded-xl border border-dashed border-gray-300
                           px-3 py-2.5 text-sm text-gray-600 hover:border-blue-300 hover:text-blue-600
                           hover:bg-blue-50 transition">
                <MessageSquarePlus className="h-4 w-4" />
                New Conversation
              </button>
            </div>

            {/* Conversation list */}
            <div className="flex-1 overflow-y-auto px-2 py-1 space-y-0.5">
              <p className="px-2 py-1 text-[10px] font-semibold uppercase tracking-wider text-gray-400">Recent</p>
              {conversations.map(conv => (
                <button key={conv.id}
                  onClick={() => handleSelectConversation(conv.id)}
                  className={`flex w-full items-center gap-2.5 rounded-lg px-2 py-2 text-left transition
                    ${currentConversationId === conv.id
                      ? 'bg-blue-50 text-blue-700'
                      : 'text-gray-600 hover:bg-gray-100'}`}
                >
                  <MessageSquare className="h-3.5 w-3.5 flex-shrink-0" />
                  <div className="flex-1 min-w-0">
                    <p className="text-xs font-medium truncate">{conv.title || 'Untitled'}</p>
                    <p className="text-[10px] text-gray-400">{format(new Date(conv.updatedAt), 'MMM d')}</p>
                  </div>
                </button>
              ))}
            </div>

            {/* User footer */}
            <div className="border-t border-gray-100 px-3 py-3">
              <div className="flex items-center gap-2.5 mb-2">
                <div className="flex h-8 w-8 items-center justify-center rounded-full bg-gray-700 text-white text-xs font-bold flex-shrink-0">
                  {user?.name?.charAt(0)?.toUpperCase() || 'U'}
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-xs font-medium text-gray-800 truncate">{user?.name}</p>
                  <p className="text-[10px] text-gray-400 truncate">{user?.email}</p>
                </div>
              </div>
              <div className="flex gap-1 flex-wrap">
                <button onClick={() => navigate('/knowledge')}
                  className="flex-1 flex items-center justify-center gap-1 rounded-lg py-1.5 text-xs text-indigo-600 hover:bg-indigo-50 transition">
                  <GitBranch className="h-3 w-3" /> Knowledge
                </button>
                <button onClick={() => navigate('/settings')}
                  className="flex-1 flex items-center justify-center gap-1 rounded-lg py-1.5 text-xs text-gray-500 hover:bg-gray-100 transition">
                  <Settings className="h-3 w-3" /> Settings
                </button>
                <button onClick={handleLogout}
                  className="flex-1 flex items-center justify-center gap-1 rounded-lg py-1.5 text-xs text-red-500 hover:bg-red-50 transition">
                  <LogOut className="h-3 w-3" /> Logout
                </button>
              </div>
            </div>
          </motion.aside>
        )}
      </AnimatePresence>

      {/* ── Main area ── */}
      <div className="flex flex-1 flex-col min-w-0">

        {/* Header */}
        <header className="flex items-center justify-between border-b border-gray-200 bg-white px-4 py-3 flex-shrink-0">
          <div className="flex items-center gap-3">
            <button onClick={toggleSidebar} className="p-1.5 rounded-lg text-gray-500 hover:bg-gray-100 transition">
              {isSidebarOpen ? <ChevronLeft className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
            </button>
            <h1 className="text-sm font-semibold text-gray-800">
              {currentConversationId ? 'Conversation' : 'New Conversation'}
            </h1>
          </div>
          <button
            onClick={() => setShowConnectors(v => !v)}
            className={`flex items-center gap-2 rounded-xl px-3 py-1.5 text-xs font-medium transition
              ${showConnectors ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'}`}
          >
            <Database className="h-3.5 w-3.5" />
            Data Sources
          </button>
        </header>

        {/* Content + optional connector panel */}
        <div className="flex flex-1 min-h-0">
          <main className="flex-1 min-w-0">
            <ChatInterface />
          </main>

          <AnimatePresence>
            {showConnectors && (
              <motion.aside
                initial={{ width: 0, opacity: 0 }}
                animate={{ width: 280, opacity: 1 }}
                exit={{ width: 0, opacity: 0 }}
                className="border-l border-gray-200 bg-white flex-shrink-0 overflow-hidden"
              >
                <ConnectorPanel />
              </motion.aside>
            )}
          </AnimatePresence>
        </div>
      </div>
    </div>
  )
}
