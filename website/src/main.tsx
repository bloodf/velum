import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
// Self-hosted fonts (latin subsets): no request to Google Fonts, which would also hand visitors' IPs to a third party.
import '@fontsource/inter-tight/latin-300.css'
import '@fontsource/inter-tight/latin-400.css'
import '@fontsource/inter-tight/latin-500.css'
import '@fontsource/inter-tight/latin-600.css'
import '@fontsource/inter-tight/latin-700.css'
import '@fontsource/ibm-plex-mono/latin-400.css'
import '@fontsource/ibm-plex-mono/latin-500.css'
import '@fontsource/instrument-serif/latin-400.css'
import '@fontsource/instrument-serif/latin-400-italic.css'
import './styles.css'
import './light-theme.css'

const root = document.getElementById('root')!
const app = (
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>
)
// Built pages are prerendered (scripts/prerender.mjs): hydrate them, inside a transition so React hydrates in
// interruptible slices instead of one long task. The dev server serves an empty root.
if (root.hasChildNodes()) React.startTransition(() => { ReactDOM.hydrateRoot(root, app) })
else ReactDOM.createRoot(root).render(app)
