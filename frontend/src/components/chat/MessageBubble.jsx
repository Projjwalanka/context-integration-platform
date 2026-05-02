import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ThumbsUp, ThumbsDown, Bot, User, Download, FileText, Table2, Image,
         Code2, Globe, AlignLeft, Loader2 } from 'lucide-react'
import { format } from 'date-fns'
import { useState } from 'react'
import toast from 'react-hot-toast'

async function downloadArtifact(url, filename) {
  const token = localStorage.getItem('auth_token')
  try {
    const res = await fetch(url, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!res.ok) throw new Error(`Download failed: ${res.status}`)
    const blob = await res.blob()
    const objectUrl = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = objectUrl
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    URL.revokeObjectURL(objectUrl)
  } catch (err) {
    toast.error('Download failed: ' + err.message)
  }
}

const ARTIFACT_META = {
  PDF:         { Icon: FileText,     label: 'PDF',        color: 'text-red-600 bg-red-50 border-red-200' },
  EXCEL:       { Icon: Table2,       label: 'Excel',      color: 'text-green-700 bg-green-50 border-green-200' },
  WORD:        { Icon: FileText,     label: 'Word',       color: 'text-blue-700 bg-blue-50 border-blue-200' },
  POWERPOINT:  { Icon: FileText,     label: 'PowerPoint', color: 'text-orange-600 bg-orange-50 border-orange-200' },
  JSON:        { Icon: Code2,        label: 'JSON',       color: 'text-yellow-700 bg-yellow-50 border-yellow-200' },
  XML:         { Icon: Code2,        label: 'XML',        color: 'text-purple-700 bg-purple-50 border-purple-200' },
  HTML:        { Icon: Globe,        label: 'HTML',       color: 'text-indigo-700 bg-indigo-50 border-indigo-200' },
  TEXT:        { Icon: AlignLeft,    label: 'Text',       color: 'text-gray-700 bg-gray-50 border-gray-200' },
  IMAGE:       { Icon: Image,        label: 'Image',      color: 'text-pink-600 bg-pink-50 border-pink-200' },
}

export default function MessageBubble({ message, onFeedback }) {
  const isAssistant = message.role === 'ASSISTANT'
  const [feedbackGiven, setFeedbackGiven] = useState(null)
  const [downloading, setDownloading] = useState({})

  const handleFeedback = (type) => {
    if (feedbackGiven) return
    setFeedbackGiven(type)
    onFeedback?.(message.id, type)
  }

  return (
    <div className={`flex gap-3 ${isAssistant ? '' : 'flex-row-reverse'}`}>
      {/* Avatar */}
      <div className={`flex-shrink-0 flex h-8 w-8 items-center justify-center rounded-full
        ${isAssistant ? 'bg-blue-600' : 'bg-gray-700'}`}>
        {isAssistant
          ? <Bot className="h-4 w-4 text-white" />
          : <User className="h-4 w-4 text-white" />
        }
      </div>

      {/* Bubble */}
      <div className={`max-w-[80%] group ${isAssistant ? '' : 'items-end flex flex-col'}`}>
        <div className={`rounded-2xl px-4 py-3 text-sm leading-relaxed
          ${isAssistant
            ? 'bg-white border border-gray-200 text-gray-800 shadow-sm'
            : 'bg-blue-600 text-white'
          }`}
        >
          {isAssistant ? (
            message.streaming && !message.content ? (
              /* Typing indicator */
              <div className="flex items-center gap-1 py-1">
                <span className="typing-dot h-2 w-2 rounded-full bg-gray-400" />
                <span className="typing-dot h-2 w-2 rounded-full bg-gray-400" />
                <span className="typing-dot h-2 w-2 rounded-full bg-gray-400" />
              </div>
            ) : (
              <div className="prose prose-sm max-w-none prose-p:my-1 prose-li:my-0">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                  {message.content}
                </ReactMarkdown>
              </div>
            )
          ) : (
            <p className="whitespace-pre-wrap">{message.content}</p>
          )}
        </div>

        {/* Artifacts */}
        {message.artifacts?.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-2">
            {message.artifacts.map((artifact, i) => {
              const meta = ARTIFACT_META[artifact.type] ?? ARTIFACT_META.TEXT
              const { Icon, label, color } = meta
              const isDownloading = !!downloading[i]
              return (
                <button
                  key={i}
                  disabled={isDownloading}
                  onClick={async () => {
                    setDownloading(d => ({ ...d, [i]: true }))
                    await downloadArtifact(artifact.downloadUrl, artifact.filename)
                    setDownloading(d => ({ ...d, [i]: false }))
                  }}
                  className={`inline-flex items-center gap-2 rounded-lg border px-3 py-1.5 text-xs font-medium transition hover:opacity-80 disabled:opacity-60 disabled:cursor-not-allowed ${color}`}
                >
                  <Icon className="h-3.5 w-3.5" />
                  <span>{artifact.filename}</span>
                  <span className="opacity-50 text-[10px] uppercase font-bold">{label}</span>
                  {isDownloading
                    ? <Loader2 className="h-3 w-3 opacity-50 animate-spin" />
                    : <Download className="h-3 w-3 opacity-50" />
                  }
                </button>
              )
            })}
          </div>
        )}

        {/* Footer: timestamp + feedback */}
        <div className={`mt-1 flex items-center gap-2 ${isAssistant ? '' : 'flex-row-reverse'}`}>
          {message.createdAt && (
            <span className="text-[11px] text-gray-400">
              {format(new Date(message.createdAt), 'HH:mm')}
            </span>
          )}
          {isAssistant && !message.streaming && onFeedback && (
            <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
              <button onClick={() => handleFeedback('THUMBS_UP')}
                className={`rounded-full p-1 transition ${feedbackGiven === 'THUMBS_UP'
                  ? 'text-green-600 bg-green-50' : 'text-gray-400 hover:text-green-600 hover:bg-green-50'}`}>
                <ThumbsUp className="h-3.5 w-3.5" />
              </button>
              <button onClick={() => handleFeedback('THUMBS_DOWN')}
                className={`rounded-full p-1 transition ${feedbackGiven === 'THUMBS_DOWN'
                  ? 'text-red-500 bg-red-50' : 'text-gray-400 hover:text-red-500 hover:bg-red-50'}`}>
                <ThumbsDown className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
