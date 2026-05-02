import { useEffect, useRef, useState } from 'react'
import { motion } from 'framer-motion'
import { Mic, Square } from 'lucide-react'
import toast from 'react-hot-toast'

/**
 * Voice input component using the Web Speech API.
 * Falls back gracefully on unsupported browsers.
 *
 * For production: connect to OpenAI Whisper via /api/voice/transcribe
 * for multi-language support and higher accuracy.
 */
export default function VoiceInput({ onResult, onStop }) {
  const [transcript, setTranscript] = useState('')
  const [listening, setListening] = useState(false)
  const recognitionRef = useRef(null)

  useEffect(() => {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition
    if (!SpeechRecognition) {
      toast.error('Voice input not supported in this browser. Try Chrome or Edge.')
      onStop()
      return
    }

    const recognition = new SpeechRecognition()
    recognition.continuous = true
    recognition.interimResults = true
    recognition.lang = 'en-US'

    recognition.onstart = () => setListening(true)
    recognition.onend  = () => setListening(false)
    recognition.onerror = (e) => {
      if (e.error !== 'aborted') toast.error('Voice recognition error: ' + e.error)
      onStop()
    }
    recognition.onresult = (e) => {
      let interim = ''
      let final = ''
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const text = e.results[i][0].transcript
        if (e.results[i].isFinal) final += text
        else interim += text
      }
      setTranscript(final || interim)
      if (final) {
        recognitionRef.current?.stop()
        onResult(final.trim())
      }
    }

    recognitionRef.current = recognition
    recognition.start()

    return () => { try { recognition.stop() } catch (_) {} }
  }, [])

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className="absolute bottom-full left-0 right-0 mb-2 rounded-2xl border border-red-200
                 bg-white p-4 shadow-lg"
    >
      <div className="flex items-center gap-3">
        {/* Animated mic icon */}
        <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-red-500">
          <Mic className="h-5 w-5 text-white" />
          {listening && (
            <span className="absolute h-10 w-10 animate-ping rounded-full bg-red-400 opacity-40" />
          )}
        </div>

        <div className="flex-1 min-w-0">
          <p className="text-xs font-medium text-gray-700 mb-1">Listening…</p>
          <p className="text-sm text-gray-500 truncate italic">
            {transcript || 'Speak now…'}
          </p>
        </div>

        <button onClick={() => { recognitionRef.current?.stop(); onStop() }}
          className="flex-shrink-0 flex items-center gap-1.5 rounded-lg border border-gray-200
                     px-3 py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50 transition">
          <Square className="h-3 w-3" /> Stop
        </button>
      </div>
    </motion.div>
  )
}
