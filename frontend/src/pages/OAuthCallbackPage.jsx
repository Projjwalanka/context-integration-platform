import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { CheckCircle2, XCircle, Loader2 } from 'lucide-react'

export default function OAuthCallbackPage() {
  const [searchParams] = useSearchParams()
  const [status, setStatus] = useState('processing') // processing | success | error

  useEffect(() => {
    const success = searchParams.get('success')
    const error = searchParams.get('error')
    const connectorId = searchParams.get('connectorId')
    const type = searchParams.get('type')

    const notify = (msg) => {
      if (window.opener) {
        window.opener.postMessage(msg, window.location.origin)
        setTimeout(() => window.close(), 1500)
      } else {
        setTimeout(() => { window.location.href = '/' }, 2500)
      }
    }

    if (error) {
      setStatus('error')
      notify({ type: 'OAUTH_ERROR', error })
      return
    }

    if (success) {
      setStatus('success')
      notify({ type: 'OAUTH_SUCCESS', connectorId, sourceType: type })
    }
  }, [searchParams])

  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center p-4">
      <div className="bg-white rounded-2xl shadow-xl p-8 max-w-xs w-full text-center">
        {status === 'processing' && (
          <>
            <Loader2 className="h-10 w-10 text-blue-500 mx-auto mb-4 animate-spin" />
            <p className="font-semibold text-gray-800">Completing authentication…</p>
            <p className="text-sm text-gray-400 mt-1">Please wait</p>
          </>
        )}
        {status === 'success' && (
          <>
            <CheckCircle2 className="h-10 w-10 text-green-500 mx-auto mb-4" />
            <p className="font-semibold text-gray-800">Connected successfully!</p>
            <p className="text-sm text-gray-400 mt-1">This window will close shortly</p>
          </>
        )}
        {status === 'error' && (
          <>
            <XCircle className="h-10 w-10 text-red-500 mx-auto mb-4" />
            <p className="font-semibold text-gray-800">Connection failed</p>
            <p className="text-sm text-gray-400 mt-1">
              {searchParams.get('error')?.replace(/_/g, ' ') || 'An error occurred during OAuth'}
            </p>
          </>
        )}
      </div>
    </div>
  )
}
