import { Navigate, Route, Routes } from 'react-router-dom'
import { MotionConfig } from 'framer-motion'
import Marketing from './Marketing'
import Docs from './Docs'
import { ScrollToTop } from './Shared'

export default function App() {
  return (
    <MotionConfig reducedMotion="user">
      <ScrollToTop />
      <a className="skip-link" href="#main-content">Skip to content</a>
      <Routes>
        <Route path="/" element={<Marketing />} />
        <Route path="/docs" element={<Navigate to="/docs/introduction" replace />} />
        <Route path="/docs/:slug" element={<Docs />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </MotionConfig>
  )
}
