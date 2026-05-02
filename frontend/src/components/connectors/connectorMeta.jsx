// Shared brand icons and metadata for all data source connectors

export function JiraIcon({ size = 20 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-label="Jira">
      <path d="M11.975 2.651a.824.824 0 00-1.163 0L5.127 8.344a.824.824 0 000 1.163l5.685 5.693c.321.32.841.32 1.163 0l5.686-5.693a.824.824 0 000-1.163l-5.686-5.693z" fill="#2684FF"/>
      <path d="M11.975 9.65a.824.824 0 00-1.163 0L5.127 15.343a.824.824 0 000 1.163l5.685 5.693c.321.32.841.32 1.163 0l5.686-5.693a.824.824 0 000-1.163L11.975 9.65z" fill="#0052CC"/>
    </svg>
  )
}

export function ConfluenceIcon({ size = 20 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-label="Confluence">
      <path d="M2.5 17.8c-.3.5-.6 1.05-.82 1.46a1.1 1.1 0 00.44 1.5l3.52 2.08a1.1 1.1 0 001.5-.44c.18-.32.5-.83.85-1.42 2-3.44 4.2-3.18 8.0-1.32l3.6-2.38a1.1 1.1 0 00.3-1.58c-.86-1.46-2.38-3.9-3.56-5.56C13.8 6.28 10.3 4.88 6 7.58c-2.1 1.26-3.56 3.6-3.22 6.04.1.72.26 1.6 0 3.08-.01.1-.02.2-.03.33.01.03 0 .08-.25.33z" fill="#0052CC"/>
      <path d="M21.5 6.2c.3-.5.6-1.05.82-1.46a1.1 1.1 0 00-.44-1.5l-3.52-2.08a1.1 1.1 0 00-1.5.44c-.18.32-.5.83-.85 1.42-2 3.44-4.2 3.18-8 1.32L4.41 6.72a1.1 1.1 0 00-.3 1.58c.86 1.46 2.38 3.9 3.56 5.56C10.2 17.72 13.7 19.12 18 16.42c2.1-1.26 3.56-3.6 3.22-6.04-.1-.72-.26-1.6 0-3.08.01-.1.02-.2.03-.33-.01-.03 0-.08.25-.33z" fill="#2684FF"/>
    </svg>
  )
}

export function GitHubIcon({ size = 20 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="#24292f" aria-label="GitHub">
      <path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"/>
    </svg>
  )
}

export function SharePointIcon({ size = 20 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-label="SharePoint">
      <circle cx="9.5" cy="6.5" r="5.5" fill="#038387"/>
      <circle cx="17" cy="12.5" r="4" fill="#1BB0B5"/>
      <circle cx="11" cy="18.5" r="3.5" fill="#37C6D0"/>
      <rect x="4" y="9.5" width="11.5" height="12" rx="1.5" fill="#038387"/>
      <path d="M7 13h6M7 16h4.5" stroke="white" strokeWidth="1.3" strokeLinecap="round"/>
    </svg>
  )
}

export function EmailIcon({ size = 20 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-label="Email">
      <rect x="2" y="4" width="20" height="16" rx="2.5" fill="#7C3AED"/>
      <path d="M2 8l10 6.5L22 8" stroke="white" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  )
}

export function DocumentsIcon({ size = 20 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-label="Documents">
      <rect x="7" y="3" width="12" height="15" rx="1.5" fill="#059669" opacity="0.3"/>
      <rect x="4" y="5" width="12" height="15" rx="1.5" fill="#059669" opacity="0.6"/>
      <rect x="1" y="3" width="14" height="17" rx="2" fill="#059669"/>
      <path d="M4.5 8h7M4.5 11h7M4.5 14h4.5" stroke="white" strokeWidth="1.3" strokeLinecap="round"/>
    </svg>
  )
}

export const CONNECTOR_META = {
  JIRA: {
    label: 'Jira',
    Icon: JiraIcon,
    bg: 'bg-blue-50',
    supportsOAuth: true,
    oauthProvider: 'Atlassian',
    description: 'Issues, sprints & project tracking',
  },
  CONFLUENCE: {
    label: 'Confluence',
    Icon: ConfluenceIcon,
    bg: 'bg-indigo-50',
    supportsOAuth: true,
    oauthProvider: 'Atlassian',
    description: 'Wiki pages & documentation spaces',
  },
  GITHUB: {
    label: 'GitHub',
    Icon: GitHubIcon,
    bg: 'bg-gray-100',
    supportsOAuth: true,
    oauthProvider: 'GitHub',
    description: 'Repositories, issues & pull requests',
  },
  SHAREPOINT: {
    label: 'SharePoint',
    Icon: SharePointIcon,
    bg: 'bg-teal-50',
    supportsOAuth: true,
    oauthProvider: 'Microsoft',
    description: 'Sites, libraries & team documents',
  },
  DOCUMENTS: {
    label: 'Documents',
    Icon: DocumentsIcon,
    bg: 'bg-green-50',
    supportsOAuth: false,
    oauthProvider: null,
    description: 'Upload PDF, DOCX, XLSX and more',
  },
}
