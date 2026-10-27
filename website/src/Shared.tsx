import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ArrowUpRight, Menu, X } from 'lucide-react'

export function Logo({ compact = false }: { compact?: boolean }) {
  return (
    <Link to="/" className="brand" aria-label="Velum home">
      <img className="brand-mark" src="/brand/velum-v.svg" alt="" aria-hidden="true" />
      {!compact && <span>Velum</span>}
    </Link>
  )
}

export function ScrollToTop() {
  const { pathname } = useLocation()
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'instant' })
  }, [pathname])
  return null
}

export function SiteHeader({ docs = false }: { docs?: boolean }) {
  const [open, setOpen] = useState(false)
  const [scrolled, setScrolled] = useState(false)
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 24)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])
  useEffect(() => setOpen(false), [useLocation().pathname])
  return (
    <header className={`site-header ${scrolled ? 'is-scrolled' : ''} ${docs ? 'docs-header' : ''}`}>
      <Logo />
      <nav className="desktop-nav" aria-label="Main navigation">
        <a href={docs ? '/#workflow' : '#workflow'}>Workflow</a>
        <a href={docs ? '/#everywhere' : '#everywhere'}>Apps</a>
        <a href={docs ? '/#open-source' : '#open-source'}>Open source</a>
        <Link to="/docs/introduction" className={docs ? 'active' : ''}>Docs</Link>
      </nav>
      <div className="header-actions">
        <a className="github-link" href="https://github.com/bloodf/velum" target="_blank" rel="noreferrer">GitHub <ArrowUpRight size={14} /></a>
        <button className="menu-toggle" onClick={() => setOpen(!open)} aria-label={open ? 'Close menu' : 'Open menu'} aria-expanded={open}>
          {open ? <X /> : <Menu />}
        </button>
      </div>
      <div className={`mobile-menu ${open ? 'is-open' : ''}`}>
        <a href={docs ? '/#workflow' : '#workflow'}>Workflow <span>01</span></a>
        <a href={docs ? '/#everywhere' : '#everywhere'}>Apps <span>02</span></a>
        <a href={docs ? '/#open-source' : '#open-source'}>Open source <span>03</span></a>
        <Link to="/docs/introduction">Documentation <span>04</span></Link>
        <a href="https://github.com/bloodf/velum" target="_blank" rel="noreferrer">GitHub <ArrowUpRight /></a>
      </div>
    </header>
  )
}

export function Waveform({ count = 42, active = false }: { count?: number; active?: boolean }) {
  return (
    <span className={`waveform ${active ? 'is-active' : ''}`} aria-hidden="true">
      {Array.from({ length: count }, (_, i) => (
        <i key={i} style={{ '--i': i, '--h': `${18 + ((i * 37) % 76)}%` } as React.CSSProperties} />
      ))}
    </span>
  )
}

export function ArrowLink({ to, children, external = false }: { to: string; children: React.ReactNode; external?: boolean }) {
  const content = <>{children}<ArrowUpRight size={18} /></>
  if (external) return <a className="arrow-link" href={to} target="_blank" rel="noreferrer">{content}</a>
  return <Link className="arrow-link" to={to}>{content}</Link>
}

export function useDocumentTitle(title: string) {
  useEffect(() => {
    document.title = title
  }, [title])
}
