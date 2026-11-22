import { lazy, Suspense, type ComponentType } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { MotionConfig } from 'framer-motion'
import { Analytics } from '@vercel/analytics/react'
import Marketing from './Marketing'
import { ScrollToTop } from './Shared'

// The docs are a separate chunk: the landing page does not download them. The prerender passes them in directly.
const LazyDocs = lazy(() => import('./Docs'))

export default function App({ docs: Docs = LazyDocs }: { docs?: ComponentType }) {
  return (
    <MotionConfig reducedMotion="user">
      <ScrollToTop />
      <a className="skip-link" href="#main-content">Skip to content</a>
      <Routes>
        <Route path="/" element={<Marketing />} />
        <Route path="/docs" element={<Navigate to="/docs/introduction" replace />} />
        <Route path="/docs/:slug" element={<Suspense fallback={null}><Docs /></Suspense>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      {/* Vercel serves the analytics script only on its own deployments; elsewhere it would be a 404. */}
      {typeof __VELUM_VERCEL_ANALYTICS__ !== 'undefined' && __VELUM_VERCEL_ANALYTICS__ && <Analytics />}
    </MotionConfig>
  )
}
