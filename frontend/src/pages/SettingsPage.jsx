import { useNavigate } from 'react-router-dom'
import { ArrowLeft, Bot, Shield, Zap, Database } from 'lucide-react'

export default function SettingsPage() {
  const navigate = useNavigate()
  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white border-b border-gray-200 px-6 py-4 flex items-center gap-3">
        <button onClick={() => navigate('/')} className="p-1.5 rounded-lg hover:bg-gray-100 transition">
          <ArrowLeft className="h-5 w-5 text-gray-600" />
        </button>
        <h1 className="font-semibold text-gray-900">Settings</h1>
      </header>

      <div className="max-w-2xl mx-auto py-8 px-6 space-y-6">
        {[
          { icon: Bot, title: 'AI Model', desc: 'GPT-4o (OpenAI) — configured via OPENAI_API_KEY', color: 'blue' },
          { icon: Database, title: 'Vector Store', desc: 'pgvector on PostgreSQL — hybrid RAG with IVFFlat index', color: 'green' },
          { icon: Shield, title: 'Guardrails', desc: 'PII detection, prompt injection, toxicity filtering, rate limiting', color: 'orange' },
          { icon: Zap, title: 'Agent Tools', desc: 'PDF, Excel, Image, Email, JSON export — 7 tools available', color: 'purple' },
        ].map(item => (
          <div key={item.title} className="card p-5 flex items-start gap-4">
            <div className={`flex h-10 w-10 items-center justify-center rounded-xl bg-${item.color}-100`}>
              <item.icon className={`h-5 w-5 text-${item.color}-600`} />
            </div>
            <div>
              <p className="font-medium text-gray-900">{item.title}</p>
              <p className="text-sm text-gray-500 mt-0.5">{item.desc}</p>
            </div>
          </div>
        ))}
        <p className="text-center text-xs text-gray-400">
          To change API keys, edit the <code className="bg-gray-100 px-1 rounded">.env</code> file and restart the service.
        </p>
      </div>
    </div>
  )
}
