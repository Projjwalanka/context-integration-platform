import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'
import { motion } from 'framer-motion'
import toast from 'react-hot-toast'
import { Bot, Lock, Mail } from 'lucide-react'

export default function LoginPage() {
  const [tab, setTab] = useState('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName]   = useState('')
  const [loading, setLoading] = useState(false)
  const { login, register } = useAuthStore()
  const navigate = useNavigate()

  const handleSubmit = async (e) => {
    e.preventDefault()
    setLoading(true)
    try {
      if (tab === 'login') {
        await login(email, password)
      } else {
        await register(email, password, firstName, lastName)
      }
      navigate('/')
    } catch (err) {
      toast.error(err.response?.data?.message || 'Authentication failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-blue-950 to-slate-900 flex items-center justify-center p-4">
      <motion.div
        initial={{ opacity: 0, y: 24 }}
        animate={{ opacity: 1, y: 0 }}
        className="w-full max-w-md"
      >
        {/* Logo */}
        <div className="flex flex-col items-center mb-8">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-blue-600 shadow-lg mb-3">
            <Bot className="h-8 w-8 text-white" />
          </div>
          <h1 className="text-2xl font-bold text-white">AI Assistant</h1>
          <p className="text-sm text-blue-300 mt-1">Enterprise Banking Intelligence Platform</p>
        </div>

        {/* Card */}
        <div className="rounded-2xl bg-white/10 backdrop-blur-xl border border-white/20 p-8 shadow-2xl">
          {/* Tabs */}
          <div className="flex rounded-lg bg-white/10 p-1 mb-6">
            {['login', 'register'].map(t => (
              <button
                key={t}
                onClick={() => setTab(t)}
                className={`flex-1 rounded-md py-2 text-sm font-medium capitalize transition ${
                  tab === t ? 'bg-white text-gray-900 shadow' : 'text-white/70 hover:text-white'
                }`}
              >
                {t === 'login' ? 'Sign In' : 'Sign Up'}
              </button>
            ))}
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            {tab === 'register' && (
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-white/70 mb-1">First Name</label>
                  <input value={firstName} onChange={e => setFirstName(e.target.value)}
                    className="input-field bg-white/10 border-white/20 text-white placeholder-white/40"
                    placeholder="John" required />
                </div>
                <div>
                  <label className="block text-xs font-medium text-white/70 mb-1">Last Name</label>
                  <input value={lastName} onChange={e => setLastName(e.target.value)}
                    className="input-field bg-white/10 border-white/20 text-white placeholder-white/40"
                    placeholder="Doe" required />
                </div>
              </div>
            )}

            <div>
              <label className="block text-xs font-medium text-white/70 mb-1">Email</label>
              <div className="relative">
                <Mail className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-white/40" />
                <input type="email" value={email} onChange={e => setEmail(e.target.value)}
                  className="input-field pl-9 bg-white/10 border-white/20 text-white placeholder-white/40"
                  placeholder="you@bank.com" required />
              </div>
            </div>

            <div>
              <label className="block text-xs font-medium text-white/70 mb-1">Password</label>
              <div className="relative">
                <Lock className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-white/40" />
                <input type="password" value={password} onChange={e => setPassword(e.target.value)}
                  className="input-field pl-9 bg-white/10 border-white/20 text-white placeholder-white/40"
                  placeholder="••••••••" required minLength={8} />
              </div>
            </div>

            {tab === 'login' && (
              <p className="text-xs text-white/50">
                Demo: <code className="bg-white/10 px-1 rounded">admin@bank.com</code> / <code className="bg-white/10 px-1 rounded">Admin@123</code>
              </p>
            )}

            <button type="submit" disabled={loading}
              className="w-full rounded-lg bg-blue-600 py-2.5 text-sm font-semibold text-white
                         shadow transition hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed mt-2">
              {loading ? 'Please wait…' : tab === 'login' ? 'Sign In' : 'Create Account'}
            </button>
          </form>
        </div>
      </motion.div>
    </div>
  )
}
