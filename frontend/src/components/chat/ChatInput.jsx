import { useState, useRef, useCallback } from 'react'
import { Send, Mic, MicOff, Paperclip } from 'lucide-react'
import VoiceInput from './VoiceInput'
import toast from 'react-hot-toast'
import client from '../../api/client'

export default function ChatInput({ onSend, disabled }) {
  const [text, setText] = useState('')
  const [voiceActive, setVoiceActive] = useState(false)
  const textareaRef = useRef(null)

  const handleSubmit = useCallback((e) => {
    e?.preventDefault()
    const trimmed = text.trim()
    if (!trimmed || disabled) return
    onSend(trimmed)
    setText('')
    textareaRef.current?.focus()
  }, [text, disabled, onSend])

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  const handleVoiceResult = (transcript) => {
    setText(prev => prev + (prev ? ' ' : '') + transcript)
    setVoiceActive(false)
    textareaRef.current?.focus()
  }

  const handleFileUpload = async (e) => {
    const file = e.target.files[0]
    if (!file) return
    const formData = new FormData()
    formData.append('file', file)
    try {
      await client.post('/ingestion/upload', formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      toast.success(`"${file.name}" uploaded and queued for ingestion`)
    } catch {
      toast.error('Upload failed')
    }
    e.target.value = ''
  }

  return (
    <div className="relative">
      <div className={`flex items-end gap-2 rounded-2xl border ${
        disabled ? 'border-gray-200 bg-gray-50' : 'border-gray-300 bg-white shadow-sm'
      } px-3 py-2 transition`}>

        {/* File upload */}
        <label className="cursor-pointer flex-shrink-0 p-1.5 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition">
          <Paperclip className="h-4 w-4" />
          <input type="file" className="hidden" onChange={handleFileUpload}
            accept=".pdf,.docx,.doc,.txt,.html,.md,.xlsx,.csv" />
        </label>

        {/* Text input */}
        <textarea
          ref={textareaRef}
          rows={1}
          value={text}
          onChange={e => { setText(e.target.value); e.target.style.height = 'auto'; e.target.style.height = Math.min(e.target.scrollHeight, 180) + 'px' }}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          placeholder={disabled ? 'AI is responding…' : 'Message AI Assistant… (Shift+Enter for new line)'}
          className="flex-1 resize-none bg-transparent text-sm leading-relaxed text-gray-800
                     placeholder-gray-400 focus:outline-none min-h-[22px] max-h-[180px]
                     disabled:cursor-not-allowed"
        />

        {/* Voice input toggle */}
        <button
          onClick={() => setVoiceActive(v => !v)}
          disabled={disabled}
          className={`flex-shrink-0 p-1.5 rounded-lg transition ${
            voiceActive
              ? 'text-red-500 bg-red-50 animate-pulse'
              : 'text-gray-400 hover:text-gray-600 hover:bg-gray-100'
          } disabled:opacity-50`}
          title={voiceActive ? 'Stop recording' : 'Voice input'}
        >
          {voiceActive ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
        </button>

        {/* Send */}
        <button
          onClick={handleSubmit}
          disabled={disabled || !text.trim()}
          className="flex-shrink-0 flex h-8 w-8 items-center justify-center rounded-xl
                     bg-blue-600 text-white shadow-sm transition hover:bg-blue-500
                     disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <Send className="h-3.5 w-3.5" />
        </button>
      </div>

      {voiceActive && (
        <VoiceInput
          onResult={handleVoiceResult}
          onStop={() => setVoiceActive(false)}
        />
      )}

      <p className="mt-1.5 text-center text-[10px] text-gray-400">
        AI can make mistakes. Verify important information before acting on it.
      </p>
    </div>
  )
}
