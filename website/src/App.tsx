import { Navigate, Route, Routes } from 'react-router-dom'
import Marketing from './Marketing'
import Docs from './Docs'
import { ScrollToTop } from './Shared'

export default function App() {
  return (
    <>
      <ScrollToTop />
      <Routes>
        <Route path="/" element={<Marketing />} />
        <Route path="/docs" element={<Navigate to="/docs/introduction" replace />} />
        <Route path="/docs/:slug" element={<Docs />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  )
}
