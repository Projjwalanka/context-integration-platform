import { create } from 'zustand'

export const useChatStore = create((set, get) => ({
  conversations: [],
  currentConversationId: null,
  messages: [],
  isStreaming: false,
  streamingContent: '',
  activeConnectors: [],
  isSidebarOpen: true,

  setConversations: (conversations) => set({ conversations }),

  setCurrentConversation: (id) => set({ currentConversationId: id, messages: [] }),

  addMessage: (message) => set(s => ({ messages: [...s.messages, message] })),

  setMessages: (messages) => set({ messages }),

  appendStreamChunk: (chunk) =>
    set(s => ({ streamingContent: s.streamingContent + chunk })),

  finalizeStream: (finalMessage) =>
    set(s => ({
      isStreaming: false,
      streamingContent: '',
      messages: [...s.messages, finalMessage],
    })),

  setStreaming: (isStreaming) => set({ isStreaming, streamingContent: isStreaming ? '' : '' }),

  toggleConnector: (connectorId) =>
    set(s => ({
      activeConnectors: s.activeConnectors.includes(connectorId)
        ? s.activeConnectors.filter(id => id !== connectorId)
        : [...s.activeConnectors, connectorId],
    })),

  toggleSidebar: () => set(s => ({ isSidebarOpen: !s.isSidebarOpen })),

  reset: () => set({ messages: [], currentConversationId: null, streamingContent: '', isStreaming: false }),
}))
