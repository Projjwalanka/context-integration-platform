import { Routes, Route, Navigate } from 'react-router-dom'
import { useAuthStore } from './store/authStore'
import LoginPage from './pages/LoginPage'
import ChatPage from './pages/ChatPage'
import SettingsPage from './pages/SettingsPage'
import OAuthCallbackPage from './pages/OAuthCallbackPage'
import KnowledgePage from './pages/KnowledgePage'

// DEV: auth bypassed — remove this and restore token check before go-live
function PrivateRoute({ children }) {
  return children
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<PrivateRoute><ChatPage /></PrivateRoute>} />
      <Route path="/knowledge" element={<PrivateRoute><KnowledgePage /></PrivateRoute>} />
      <Route path="/settings" element={<PrivateRoute><SettingsPage /></PrivateRoute>} />
      <Route path="/oauth/callback" element={<OAuthCallbackPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
