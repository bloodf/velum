import { useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { ArrowLeft, ArrowRight, ArrowUpRight, BookOpen, Check, ChevronRight, Clipboard, Command, ExternalLink, FileText, Github, Menu, Search, X } from 'lucide-react'
import { Link, Navigate, useParams } from 'react-router-dom'
import { DocBlock, docs, docSections, getDoc } from './content'
import { Logo, useDocumentTitle } from './Shared'

const github = 'https://github.com/bloodf/velum'

function SearchDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [query, setQuery] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  const results = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return docs.slice(0, 5)
    return docs.filter((doc) => [doc.title, doc.description, doc.section, ...doc.keywords, ...doc.blocks.map((block) => 'text' in block ? block.text : '')].join(' ').toLowerCase().includes(q))
  }, [query])
  useEffect(() => {
    if (open) {
      setQuery('')
      document.body.classList.add('modal-open')
      window.setTimeout(() => inputRef.current?.focus(), 80)
    } else document.body.classList.remove('modal-open')
    return () => document.body.classList.remove('modal-open')
  }, [open])
  return (
    <AnimatePresence>
      {open && <motion.div className="search-modal-backdrop" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
        <motion.div className="search-modal" role="dialog" aria-modal="true" aria-label="Search documentation" initial={{ opacity: 0, y: -18, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: -10, scale: 0.98 }} transition={{ duration: 0.22, ease: [0.22, 1, 0.36, 1] }}>
          <div className="search-modal-input"><Search size={20} /><input ref={inputRef} name="documentation-search" type="search" autoComplete="off" spellCheck={false} value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search guides, apps, recorders…" aria-label="Search documentation" /><button onClick={onClose}>ESC</button></div>
          <div className="search-results">
            <span className="micro-label">{query ? `${results.length} RESULT${results.length === 1 ? '' : 'S'}` : 'QUICK LINKS'}</span>
            {results.length ? results.map((doc) => <Link key={doc.slug} to={`/docs/${doc.slug}`} onClick={onClose} className="search-result"><span className="search-result-icon"><FileText size={18} /></span><span><small>{doc.section}</small><strong>{doc.title}</strong><p>{doc.description}</p></span><ArrowRight size={17} /></Link>) : <div className="search-empty"><Search size={28} /><strong>No results for “{query}”</strong><p>Try a broader phrase, such as “mobile”, “Plaud”, or “privacy”.</p></div>}
          </div>
          <div className="search-modal-footer"><span><kbd>↵</kbd> Open</span><span><kbd>esc</kbd> Close</span><span>VELUM DOCS</span></div>
        </motion.div>
      </motion.div>}
    </AnimatePresence>
  )
}

function DocsHeader({ onSearch, onMenu }: { onSearch: () => void; onMenu: () => void }) {
  return (
    <header className="docs-topbar">
      <div className="docs-topbar-brand"><button className="docs-menu-button" onClick={onMenu} aria-label="Open documentation navigation"><Menu size={20} /></button><Logo /><span className="docs-badge">DOCS</span></div>
      <button className="docs-search-trigger" onClick={onSearch}><Search size={16} /><span>Search documentation</span><kbd><Command size={11} /> K</kbd></button>
      <nav className="docs-top-links" aria-label="Project links"><Link to="/">Website</Link><a href={github} target="_blank" rel="noreferrer"><Github size={16} /> GitHub <ArrowUpRight size={13} /></a></nav>
    </header>
  )
}

function DocsSidebar({ slug, open, onClose }: { slug: string; open: boolean; onClose: () => void }) {
  return (
    <>
      <AnimatePresence>{open && <motion.div className="docs-sidebar-backdrop" onClick={onClose} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} />}</AnimatePresence>
      <aside className={`docs-sidebar ${open ? 'is-open' : ''}`}>
        <div className="docs-sidebar-mobile"><span className="micro-label">DOCUMENTATION</span><button onClick={onClose} aria-label="Close navigation"><X size={20} /></button></div>
        <div className="docs-version"><span className="live-dot" /> PRODUCT DOCUMENTATION <span>2026</span></div>
        <nav aria-label="Documentation">
          {docSections.map((section) => <div className="docs-nav-section" key={section}><span className="docs-nav-heading">{section}</span>{docs.filter((doc) => doc.section === section).map((doc) => <Link key={doc.slug} to={`/docs/${doc.slug}`} onClick={onClose} className={slug === doc.slug ? 'active' : ''}><span>{doc.title}</span>{slug === doc.slug && <ChevronRight size={14} />}</Link>)}</div>)}
        </nav>
        <div className="docs-sidebar-card"><BookOpen size={19} /><strong>Build with us</strong><p>Found a gap or something unclear? Help improve these docs.</p><a href={`${github}/issues`} target="_blank" rel="noreferrer">Open an issue <ArrowUpRight size={14} /></a></div>
      </aside>
    </>
  )
}

function CodeBlock({ block }: { block: Extract<DocBlock, { type: 'code' }> }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    await navigator.clipboard.writeText(block.code)
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1600)
  }
  return <div className="docs-code"><div><span>{block.language}</span><button onClick={copy} aria-label="Copy code" aria-live="polite">{copied ? <Check size={14} /> : <Clipboard size={14} />}{copied ? 'Copied' : 'Copy'}</button></div><pre><code>{block.code}</code></pre></div>
}

function BlockRenderer({ block }: { block: DocBlock }) {
  switch (block.type) {
    case 'paragraph': return <p>{block.text}</p>
    case 'heading': return <h2 id={block.id}>{block.text}<a href={`#${block.id}`} aria-label={`Link to ${block.text}`}>#</a></h2>
    case 'list': return block.ordered ? <ol>{block.items.map((item) => <li key={item}>{item}</li>)}</ol> : <ul>{block.items.map((item) => <li key={item}>{item}</li>)}</ul>
    case 'code': return <CodeBlock block={block} />
    case 'callout': return <div className={`docs-callout ${block.tone === 'warning' ? 'warning' : ''}`}><span>{block.tone === 'warning' ? '!' : 'i'}</span><div><strong>{block.title}</strong><p>{block.text}</p></div></div>
    case 'table': return <div className="docs-table-wrap"><table><thead><tr>{block.headers.map((header) => <th key={header}>{header}</th>)}</tr></thead><tbody>{block.rows.map((row) => <tr key={row[0]}>{row.map((cell) => <td key={cell}>{cell}</td>)}</tr>)}</tbody></table></div>
  }
}

export default function Docs() {
  const { slug } = useParams()
  const doc = getDoc(slug)
  const [searchOpen, setSearchOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [activeHeading, setActiveHeading] = useState('')
  useDocumentTitle(doc ? `${doc.title} — Velum Docs` : 'Velum Docs')
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setSearchOpen((value) => !value)
      }
      if (event.key === 'Escape') setSearchOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])
  useEffect(() => {
    if (!doc) return
    const headings = doc.blocks.filter((block) => block.type === 'heading').map((block) => block.id)
    const observer = new IntersectionObserver((entries) => entries.forEach((entry) => { if (entry.isIntersecting) setActiveHeading(entry.target.id) }), { rootMargin: '-15% 0px -70% 0px' })
    headings.forEach((id) => { const el = document.getElementById(id); if (el) observer.observe(el) })
    return () => observer.disconnect()
  }, [doc])
  if (!doc) return <Navigate to="/docs/introduction" replace />
  const index = docs.findIndex((item) => item.slug === doc.slug)
  const previous = docs[index - 1]
  const next = docs[index + 1]
  const headings = doc.blocks.filter((block) => block.type === 'heading') as Extract<DocBlock, { type: 'heading' }>[]
  return (
    <div className="docs-page">
      <DocsHeader onSearch={() => setSearchOpen(true)} onMenu={() => setMenuOpen(true)} />
      <DocsSidebar slug={doc.slug} open={menuOpen} onClose={() => setMenuOpen(false)} />
      <main className="docs-main" id="main-content">
        <article className="docs-article">
          <div className="docs-breadcrumb"><Link to="/docs/introduction">Velum</Link><ChevronRight size={12} /><span>{doc.section}</span><ChevronRight size={12} /><span>{doc.title}</span></div>
          <div className="docs-article-heading"><span className="micro-label">{doc.section.toUpperCase()} / {doc.readTime.toUpperCase()} READ</span><h1>{doc.title}</h1><p>{doc.description}</p></div>
          <div className="docs-mobile-toc"><span>On this page</span><select aria-label="On this page" onChange={(e) => { window.location.hash = e.target.value }}><option>Jump to section</option>{headings.map((heading) => <option key={heading.id} value={heading.id}>{heading.text}</option>)}</select></div>
          <div className="docs-content">{doc.blocks.map((block, blockIndex) => <BlockRenderer key={block.type === 'heading' ? block.id : `${block.type}-${blockIndex}`} block={block} />)}</div>
          <div className="docs-edit"><span>Was this guide useful?</span><a href={`${github}/issues`} target="_blank" rel="noreferrer">Give feedback <ArrowUpRight size={14} /></a><a href={github} target="_blank" rel="noreferrer"><ExternalLink size={14} /> Edit on GitHub</a></div>
          <nav className="docs-pagination" aria-label="Documentation pagination">
            {previous ? <Link to={`/docs/${previous.slug}`} className="previous"><span><ArrowLeft size={15} /> Previous</span><strong>{previous.title}</strong></Link> : <span />}
            {next ? <Link to={`/docs/${next.slug}`} className="next"><span>Next <ArrowRight size={15} /></span><strong>{next.title}</strong></Link> : <Link to="/" className="next"><span>Next <ArrowRight size={15} /></span><strong>Back to Velum</strong></Link>}
          </nav>
          <footer className="docs-article-footer"><Logo compact /><span>OPEN-SOURCE AI NOTETAKER</span><span>© 2026 VELUM</span></footer>
        </article>
        <aside className="docs-toc"><span className="micro-label">ON THIS PAGE</span>{headings.map((heading) => <a key={heading.id} href={`#${heading.id}`} className={activeHeading === heading.id ? 'active' : ''}>{heading.text}</a>)}<div className="docs-toc-help"><span>Need a hand?</span><a href={`${github}/discussions`} target="_blank" rel="noreferrer">Ask the community <ArrowUpRight size={13} /></a></div></aside>
      </main>
      <SearchDialog open={searchOpen} onClose={() => setSearchOpen(false)} />
    </div>
  )
}
