// Build-time prerender entry (scripts/prerender.mjs): the same App, rendered to HTML for each route, so the page
// paints before its JavaScript runs. The client hydrates it (main.tsx).
import { StrictMode } from 'react'
import { renderToString } from 'react-dom/server'
import { StaticRouter } from 'react-router-dom'
import App from './App'
import Docs from './Docs'
import { docs } from './content'

export const routes = ['/', ...docs.map((doc) => `/docs/${doc.slug}`)]

export function render(url: string): string {
  return renderToString(
    <StrictMode>
      <StaticRouter location={url}>
        <App docs={Docs} />
      </StaticRouter>
    </StrictMode>,
  )
}
