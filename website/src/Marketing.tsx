import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion, MotionValue, useMotionValueEvent, useScroll, useTransform } from 'framer-motion'
import { ArrowDown, ArrowRight, ArrowUpRight, Check, ChevronDown, Circle, FileAudio, Github, Headphones, Mic, Monitor, Pause, Play, Plus, Search, Smartphone, Sparkles, Upload, Waves } from 'lucide-react'
import { Link } from 'react-router-dom'
import WaveField from './WaveField'
import { ArrowLink, SiteHeader, useDocumentTitle, Waveform } from './Shared'

const github = 'https://github.com/bloodf/velum'

function RotatingThought() {
  const words = ['a decision', 'an idea', 'a follow-up', 'your next move']
  const [index, setIndex] = useState(0)
  useEffect(() => {
    const timer = window.setInterval(() => setIndex((i) => (i + 1) % words.length), 2800)
    return () => window.clearInterval(timer)
  }, [words.length])
  return (
    <span className="rotating-thought">
      <AnimatePresence mode="wait">
        <motion.span key={words[index]} initial={{ y: 18, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: -18, opacity: 0 }} transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}>
          {words[index]}
        </motion.span>
      </AnimatePresence>
    </span>
  )
}

function Hero() {
  const ref = useRef<HTMLElement>(null)
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start start', 'end start'] })
  const opacity = useTransform(scrollYProgress, [0, 0.82], [1, 0])
  const y = useTransform(scrollYProgress, [0, 1], [0, 160])
  const scale = useTransform(scrollYProgress, [0, 1], [1, 0.94])
  return (
    <section className="hero" ref={ref}>
      <WaveField />
      <div className="hero-grid" aria-hidden="true" />
      <motion.div className="hero-brand-mark" initial={{ opacity: 0, y: 25, rotate: -8 }} animate={{ opacity: 1, y: 0, rotate: 0 }} transition={{ duration: 1.1, delay: .45, ease: [0.16, 1, 0.3, 1] }}>
        <img src="/brand/icon-3d-light.webp" alt="Velum 3D logo" />
      </motion.div>
      <div className="hero-glass-note note-left"><span>01</span> AI notetakers + recorders</div>
      <div className="hero-glass-note note-right"><span>02</span> Open by design</div>
      <motion.div className="hero-content" style={{ opacity, y, scale }}>
        <div className="hero-kicker hero-enter"><span className="live-dot" /> Open-source AI notetaker <span className="kicker-divider" /> Desktop + mobile</div>
        <h1 className="hero-title" aria-label="Every word has a place">
          <span className="title-mask"><motion.span initial={{ y: '110%' }} animate={{ y: 0 }} transition={{ duration: 1, delay: 0.12, ease: [0.16, 1, 0.3, 1] }}>EVERY WORD</motion.span></span>
          <span className="title-mask"><motion.span className="title-outline" initial={{ y: '110%' }} animate={{ y: 0 }} transition={{ duration: 1, delay: 0.24, ease: [0.16, 1, 0.3, 1] }}>HAS A PLACE.</motion.span></span>
        </h1>
        <div className="hero-bottom hero-enter">
          <div className="hero-intro">
            <span className="micro-label">From passing sound to lasting clarity</span>
            <p>One open home for recordings, transcripts, and notes—across your devices and the AI notetakers you already use.</p>
          </div>
          <div className="hero-cta-group">
            <a className="button button-accent" href="#workflow">See how it flows <ArrowDown size={17} /></a>
            <Link className="text-link" to="/docs/introduction">Read the docs <ArrowUpRight size={16} /></Link>
          </div>
        </div>
      </motion.div>
      <div className="hero-side-note">AUDIO <span>→</span> UNDERSTANDING</div>
      <div className="hero-transmission">
        <span className="transmission-status"><Circle size={7} fill="currentColor" /> LIVE SIGNAL</span>
        <Waveform count={26} active />
        <span className="transmission-time">00:04:12</span>
      </div>
      <a href="#manifesto" className="scroll-cue" aria-label="Scroll to discover"><span>SCROLL TO LISTEN</span><ArrowDown size={14} /></a>
    </section>
  )
}

function Manifesto() {
  return (
    <section className="manifesto light-section" id="manifesto">
      <div className="section-rule reveal">
        <span className="micro-label">01 / WHY VELUM</span>
        <span className="micro-label">LESS ARCHIVE. MORE RECALL.</span>
      </div>
      <div className="manifesto-layout">
        <h2 className="manifesto-title reveal">Not another<br />recorder. <span className="accent-serif">The open layer</span><br />between what was said<br />and <span className="circled-word">what happens next.<svg viewBox="0 0 400 90" preserveAspectRatio="none" aria-hidden="true"><path d="M7 50C20 4 359-2 390 36C418 75 88 102 16 66C-3 57 4 38 24 28" /></svg></span></h2>
        <div className="manifesto-aside reveal">
          <Waves size={30} strokeWidth={1.4} />
          <p>Velum turns conversations into knowledge you can return to—without asking you to abandon the apps and recorders that already fit your life.</p>
          <ArrowLink to="/docs/introduction">Meet Velum</ArrowLink>
        </div>
      </div>
      <div className="principle-rows">
        <div className="principle-row reveal"><span className="row-number">01</span><h3>Capture anywhere</h3><p>Desktop, mobile, or a dedicated AI recorder. Start where the conversation is.</p><Mic /></div>
        <div className="principle-row reveal"><span className="row-number">02</span><h3>Keep the context</h3><p>Audio, words, and meaning stay connected. Never just a detached summary.</p><Headphones /></div>
        <div className="principle-row reveal"><span className="row-number">03</span><h3>Return to the moment</h3><p>Find the phrase, decision, or follow-up without digging through a file archive.</p><Search /></div>
      </div>
    </section>
  )
}

type Scene = { label: string; title: string; description: string; kind: string }
const scenes: Scene[] = [
  { label: 'CAPTURE', title: 'Start with the real thing.', description: 'A meeting, a voice memo, or a recording from your AI notetaker or external recorder. Velum keeps the original conversation at the center.', kind: 'record' },
  { label: 'TRANSCRIBE', title: 'Give every voice its words.', description: 'Move through timestamped speech with speaker context, while the source audio stays one tap away.', kind: 'transcript' },
  { label: 'DISTILL', title: 'See what actually matters.', description: 'Turn the conversation into a reviewable note with key points, decisions, and next steps.', kind: 'note' },
  { label: 'REDISCOVER', title: 'Pick up where you left off.', description: 'Search for the detail you remember and land back inside its full context.', kind: 'search' },
]

function ConversationScene({ scene, index, progress }: { scene: Scene; index: number; progress: MotionValue<number> }) {
  const start = index / scenes.length
  const end = (index + 1) / scenes.length
  const opacity = useTransform(progress, [Math.max(0, start - 0.08), start + 0.035, end - 0.035, Math.min(1, end + 0.08)], index === 0 ? [1, 1, 1, 0] : index === scenes.length - 1 ? [0, 1, 1, 1] : [0, 1, 1, 0])
  const x = useTransform(progress, [start, end], [index === 0 ? 0 : 55, index === scenes.length - 1 ? 0 : -35])
  return (
    <motion.div className={`conversation-scene scene-${scene.kind}`} style={{ opacity, x }}>
      <div className="conversation-topbar"><div className="mini-brand">v<span>·</span></div><span>Product sync / Thursday</span><span className="scene-top-meta">OCT 27 · 42 MIN</span></div>
      {scene.kind === 'record' && (
        <div className="record-interface">
          <div className="record-orbit"><span className="orbit-ring ring-one" /><span className="orbit-ring ring-two" /><button className="record-core" aria-label="Recording visualization"><Pause size={24} fill="currentColor" /></button></div>
          <span className="record-time">00:18:42</span>
          <Waveform count={54} active />
          <span className="micro-label">RECORDING · STUDIO MICROPHONE</span>
        </div>
      )}
      {scene.kind === 'transcript' && (
        <div className="transcript-interface">
          <div className="transcript-play"><button aria-label="Play transcript"><Play size={16} fill="currentColor" /></button><span>18:42</span><div className="audio-track"><i /></div><span>42:10</span></div>
          <div className="speaker-line"><span className="speaker-time">18:42</span><div><strong>MAYA</strong><p>Let’s make the imported recording feel like it belongs here, not like an attachment.</p></div></div>
          <div className="speaker-line active-line"><span className="speaker-time">18:57</span><div><strong>LEO</strong><p>So the note, the transcript, and the original audio should always stay connected.</p></div></div>
          <div className="speaker-line muted-line"><span className="speaker-time">19:16</span><div><strong>MAYA</strong><p>Exactly. Start with the source, then make it useful.</p></div></div>
        </div>
      )}
      {scene.kind === 'note' && (
        <div className="note-interface">
          <span className="micro-label">GENERATED NOTE · REVIEWED</span>
          <h3>Keep every layer connected</h3>
          <p className="note-summary">The team aligned on a source-first experience for imported conversations, with one continuous path from summary to original audio.</p>
          <div className="note-columns"><div><span className="micro-label">DECISIONS</span><p><Check size={14} /> One conversation, three linked views</p><p><Check size={14} /> Preserve imported source files</p></div><div><span className="micro-label">NEXT UP</span><p><ArrowRight size={14} /> Prototype mobile import</p><p><ArrowRight size={14} /> Test recorder export flow</p></div></div>
        </div>
      )}
      {scene.kind === 'search' && (
        <div className="search-interface">
          <div className="search-query"><Search size={22} /><span>What did we decide about imports?</span><kbd>↵</kbd></div>
          <span className="micro-label">BEST MATCH · PRODUCT SYNC</span>
          <blockquote>“The note, the transcript, and the original audio should always stay connected.”</blockquote>
          <div className="search-source"><span>LEO · 18:57</span><button aria-label="Play source"><Play size={13} fill="currentColor" /></button></div>
          <div className="answer-line"><Sparkles size={16} /><p>Keep imported audio linked to its transcript and generated note.</p></div>
        </div>
      )}
      <div className="conversation-status"><span><span className="live-dot" /> VELUM WORKSPACE</span><span>{scene.label} / 0{index + 1}</span></div>
    </motion.div>
  )
}

function Workflow() {
  const ref = useRef<HTMLElement>(null)
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start start', 'end end'] })
  const [active, setActive] = useState(0)
  useMotionValueEvent(scrollYProgress, 'change', (value) => setActive(Math.min(3, Math.floor(value * 4))))
  return (
    <section className="workflow" id="workflow" ref={ref}>
      <div className="workflow-sticky">
        <div className="workflow-copy">
          <span className="micro-label">02 / ONE CONTINUOUS FLOW</span>
          <h2>Sound in.<br /><span>Sense out.</span></h2>
          <div className="scene-copy-window">
            <AnimatePresence mode="wait">
              <motion.div key={active} initial={{ opacity: 0, y: 22 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -15 }} transition={{ duration: 0.35 }}>
                <span className="scene-label">0{active + 1} — {scenes[active].label}</span>
                <h3>{scenes[active].title}</h3>
                <p>{scenes[active].description}</p>
              </motion.div>
            </AnimatePresence>
          </div>
          <div className="scene-progress">{scenes.map((scene, i) => <span key={scene.kind} className={i <= active ? 'active' : ''}><i />{scene.label}</span>)}</div>
        </div>
        <div className="conversation-window">
          {scenes.map((scene, index) => <ConversationScene key={scene.kind} scene={scene} index={index} progress={scrollYProgress} />)}
          <div className="conversation-topbar persistent-chrome"><div className="mini-brand">v<span>·</span></div><span>Product sync / Thursday</span><span className="scene-top-meta">OCT 27 · 42 MIN</span></div>
          <div className="conversation-status persistent-chrome"><span><span className="live-dot" /> VELUM WORKSPACE</span><span>{scenes[active].label} / 0{active + 1}</span></div>
          <div className="window-corner corner-tl" /><div className="window-corner corner-br" />
        </div>
      </div>
    </section>
  )
}

function DesktopUI() {
  return (
    <div className="desktop-ui">
      <div className="desktop-sidebar"><div className="mini-brand">v<span>·</span></div><span className="side-icon active"><Search size={14} /></span><span className="side-icon"><Mic size={14} /></span><span className="side-icon"><FileAudio size={14} /></span><span className="side-avatar">HV</span></div>
      <div className="desktop-list"><span className="micro-label">LIBRARY</span><div className="library-search"><Search size={12} /> Search conversations</div><span className="list-date">TODAY</span><div className="conversation-item active"><i /><div><strong>Product sync</strong><span>42 min · Note ready</span></div></div><div className="conversation-item"><i /><div><strong>Research interview</strong><span>1 hr 08 · Transcript</span></div></div><span className="list-date">YESTERDAY</span><div className="conversation-item"><i /><div><strong>Ideas for Velum</strong><span>12 min · Voice note</span></div></div></div>
      <div className="desktop-note"><div className="note-header"><div><span className="micro-label">PRODUCT / TEAM</span><h3>Product sync</h3></div><button aria-label="More options">•••</button></div><div className="note-meta"><span>Oct 27, 2026</span><span>4 participants</span><span>42:10</span></div><div className="generated-note"><span className="micro-label"><Sparkles size={12} /> SUMMARY</span><p>The team aligned on a source-first experience for imported recordings, connecting the original audio, transcript, and note.</p><h4>Decisions</h4><p><Check size={13} /> Keep every generated layer connected to its source.</p><p><Check size={13} /> Design one shared model for desktop and mobile.</p><h4>Action items</h4><div className="action-item"><span className="checkbox" /><p>Prototype the mobile import flow</p><span>Maya</span></div><div className="action-item"><span className="checkbox" /><p>Test recorder audio and transcript exports</p><span>Leo</span></div></div></div>
    </div>
  )
}

function PhoneUI() {
  return (
    <div className="phone-ui">
      <div className="phone-status"><span>9:41</span><span>● ◒</span></div>
      <div className="phone-heading"><span className="micro-label">YOUR LIBRARY</span><h3>Good morning.</h3></div>
      <div className="phone-search"><Search size={13} /> Find a conversation</div>
      <span className="list-date">TODAY</span>
      <div className="phone-conversation"><span className="phone-conv-icon"><Waves size={18} /></span><div><strong>Product sync</strong><span>42 min · 4 people</span></div><ArrowUpRight size={14} /></div>
      <div className="phone-note"><span className="micro-label">SUMMARY</span><p>A source-first experience for every imported conversation.</p><span className="phone-note-link">Read note <ArrowRight size={12} /></span></div>
      <button className="phone-record" aria-label="Start a recording"><Plus size={24} /></button>
      <div className="phone-tabbar"><span>Library</span><span>Search</span><span>Settings</span></div>
    </div>
  )
}

function Everywhere() {
  const sectionRef = useRef<HTMLElement>(null)
  const { scrollYProgress } = useScroll({ target: sectionRef, offset: ['start end', 'end start'] })
  const desktopY = useTransform(scrollYProgress, [0, 1], [70, -50])
  const phoneY = useTransform(scrollYProgress, [0, 1], [150, -90])
  return (
    <section className="everywhere light-section" id="everywhere" ref={sectionRef}>
      <div className="section-rule reveal"><span className="micro-label">03 / DESKTOP + MOBILE</span><span className="micro-label">ONE THOUGHT. EVERY SCREEN.</span></div>
      <div className="everywhere-layout">
        <div className="everywhere-copy reveal"><h2>YOUR<br />SECOND<br /><span>MEMORY.</span></h2><p>Capture at your desk. Review on the move. Velum is being designed as one coherent workspace, not a collection of disconnected apps.</p><div className="platform-links"><Link to="/docs/desktop"><Monitor size={18} /> Desktop <ArrowUpRight size={15} /></Link><Link to="/docs/mobile"><Smartphone size={18} /> Mobile <ArrowUpRight size={15} /></Link></div></div>
        <div className="device-stage reveal">
          <div className="device-grid" aria-hidden="true" />
          <motion.div className="desktop-shell" style={{ y: desktopY }}><div className="shell-top"><span /><span /><span /><i>VELUM — PRODUCT SYNC</i></div><DesktopUI /></motion.div>
          <motion.div className="phone-shell" style={{ y: phoneY }}><div className="phone-speaker" /><PhoneUI /></motion.div>
          <div className="device-caption"><span>01 — DESKTOP WORKSPACE</span><span>02 — MOBILE LIBRARY</span></div>
        </div>
      </div>
    </section>
  )
}

const sources = [
  { name: 'Plaud', type: 'AI notetaker', detail: 'Bring conversations from your Plaud AI notetaker into one connected Velum workspace.', icon: Headphones },
  { name: 'Bee', type: 'AI notetaker', detail: 'Keep conversations captured with Bee connected to transcripts and useful notes in Velum.', icon: Headphones },
  { name: 'Limitless', type: 'AI notetaker', detail: 'Give conversations from your Limitless device one open home for audio, context, and notes.', icon: Headphones },
  { name: 'Omi', type: 'AI notetaker', detail: 'Bring Omi conversations into the same searchable Velum library as the rest of your recordings.', icon: Headphones },
  { name: 'Soundcore Work', type: 'AI notetaker', detail: 'Connect your Soundcore Work recording workflow to Velum’s transcript and note experience.', icon: Headphones },
  { name: 'iFLYTEK', type: 'AI notetaker', detail: 'Organize conversations from supported iFLYTEK devices alongside the rest of your Velum knowledge.', icon: Headphones },
  { name: 'External recorders', type: 'External audio recorders', detail: 'Velum supports external audio recorders too, giving your recordings a transcript, structured note, and searchable home.', icon: Mic },
]

function Integrations() {
  const [selected, setSelected] = useState(0)
  const source = sources[selected]
  return (
    <section className="integrations light-section" id="supported-devices">
      <div className="section-rule reveal"><span className="micro-label">04 / SUPPORTED DEVICES + RECORDERS</span><span className="micro-label">OPEN MEANS OPEN</span></div>
      <div className="integrations-heading reveal"><h2>Your recorder.<br /><span>Your notes. One place.</span></h2><p>Velum supports dedicated AI notetakers and external audio recorders, bringing conversations from the devices you already use into one open workspace.</p></div>
      <div className="integration-lab reveal">
        <div className="source-list" role="tablist" aria-label="Supported AI notetakers and external audio recorders">
          {sources.map((item, i) => <button key={item.name} role="tab" aria-selected={selected === i} className={selected === i ? 'active' : ''} onClick={() => setSelected(i)}><span className="source-index">0{i + 1}</span><item.icon size={21} strokeWidth={1.5} /><span><strong>{item.name}</strong><small>{item.type}</small></span><ArrowRight size={18} /></button>)}
        </div>
        <div className="import-visual" role="tabpanel">
          <div className="import-grid" aria-hidden="true" />
          <AnimatePresence mode="wait">
            <motion.div className="import-path" key={source.name} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.25 }}>
              <div className="import-source"><source.icon size={32} strokeWidth={1.2} /><span>{source.name}</span><small>SUPPORTED SOURCE</small></div>
              <div className="import-connector"><i /><span><Upload size={16} /></span><i /></div>
              <div className="import-destination"><div className="mini-brand">v<span>·</span></div><span>One conversation</span><small>AUDIO + TRANSCRIPT + NOTE</small></div>
            </motion.div>
          </AnimatePresence>
          <AnimatePresence mode="wait"><motion.p key={source.detail} className="import-description" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>{source.detail}</motion.p></AnimatePresence>
          <Link to="/docs/recorders" className="import-doc-link">Explore device and recorder workflows <ArrowUpRight size={16} /></Link>
        </div>
      </div>
    </section>
  )
}

function OpenSource() {
  return (
    <section className="open-source" id="open-source">
      <div className="open-source-grid" aria-hidden="true" />
      <div className="section-rule reveal"><span className="micro-label">05 / OPEN BY DESIGN</span><Github size={18} /></div>
      <div className="open-source-main">
        <h2 className="reveal">NO<br /><span>BLACK</span><br />BOXES.</h2>
        <div className="open-source-copy reveal"><p>A notetaker holds some of your most meaningful context. You should be able to see how it works, question how it works, and help make it work better.</p><ArrowLink to={github} external>Explore the repository</ArrowLink></div>
      </div>
      <div className="code-ribbon reveal"><span>OPEN SOURCE</span><i>/</i><span>YOUR CONVERSATIONS</span><i>/</i><span>YOUR CONTEXT</span><i>/</i><span>YOUR MOVE</span></div>
      <div className="open-principles">
        <div className="reveal"><span>01</span><h3>Inspect it</h3><p>Follow the implementation and the trade-offs in public.</p></div>
        <div className="reveal"><span>02</span><h3>Extend it</h3><p>Build around your workflow instead of waiting for permission.</p></div>
        <div className="reveal"><span>03</span><h3>Shape it</h3><p>Bring real problems, useful feedback, and thoughtful contributions.</p></div>
      </div>
    </section>
  )
}

const faqs = [
  ['What is Velum?', 'Velum is an open-source AI notetaker for desktop and mobile. It is designed to connect recordings, transcripts, structured notes, and rediscovery in one coherent workspace.'],
  ['Which AI notetakers and recorders does Velum support?', 'Velum supports Plaud, Bee, Limitless, Omi, Soundcore Work, and iFLYTEK devices, as well as external audio recorders. Your recordings, available transcripts, and generated notes can stay connected in one workspace.'],
  ['Which platforms will Velum support?', 'Velum is being built as a cross-platform product for desktop and mobile. Follow the repository and releases for the current platform availability.'],
  ['Can I inspect or contribute to the code?', 'Yes. Velum is open source. Explore the GitHub repository, open a focused issue, improve the documentation, or pick up a scoped contribution.'],
  ['Is AI-generated content always accurate?', 'No. Transcripts and generated notes can miss context or misinterpret speech. Velum keeps the source close so you can verify important names, decisions, and action items.'],
]

function FAQ() {
  const [open, setOpen] = useState(0)
  return (
    <section className="faq light-section">
      <div className="section-rule reveal"><span className="micro-label">06 / THE SHORT ANSWERS</span><span className="micro-label">STILL CURIOUS?</span></div>
      <div className="faq-layout"><h2 className="reveal">BEFORE<br />YOU ASK.</h2><div className="faq-list reveal">{faqs.map(([question, answer], i) => <div className={`faq-item ${open === i ? 'open' : ''}`} key={question}><button onClick={() => setOpen(open === i ? -1 : i)} aria-expanded={open === i}><span>0{i + 1}</span><strong>{question}</strong><ChevronDown size={20} /></button><AnimatePresence initial={false}>{open === i && <motion.div className="faq-answer" initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.32, ease: [0.22, 1, 0.36, 1] }}><p>{answer}</p></motion.div>}</AnimatePresence></div>)}</div></div>
    </section>
  )
}

function FinalCTA() {
  return (
    <>
      <section className="final-cta">
        <WaveField />
        <div className="final-cta-inner reveal"><span className="micro-label"><span className="live-dot" /> YOUR NEXT CONVERSATION STARTS HERE</span><h2>MAKE IT<br /><span>REMEMBERABLE.</span></h2><p>Less digging. More context. A second memory you can help build.</p><div className="final-actions"><a className="button button-accent" href={github} target="_blank" rel="noreferrer">Build Velum with us <Github size={18} /></a><Link className="button button-ghost" to="/docs/quick-start">Read the quick start <ArrowUpRight size={17} /></Link></div></div>
      </section>
      <footer className="site-footer"><div className="footer-top"><Link to="/" className="footer-wordmark">velum<span>®</span></Link><RotatingThought /></div><div className="footer-bottom"><span>OPEN-SOURCE AI NOTETAKER</span><div><Link to="/docs/introduction">Docs</Link><a href={github} target="_blank" rel="noreferrer">GitHub</a><a href="#manifesto">Back to top ↑</a></div><span>© 2026 VELUM</span></div></footer>
    </>
  )
}

export default function Marketing() {
  useDocumentTitle('Velum — Every word has a place')
  useEffect(() => {
    const observer = new IntersectionObserver((entries) => entries.forEach((entry) => { if (entry.isIntersecting) entry.target.classList.add('is-visible') }), { threshold: 0.12, rootMargin: '0px 0px -5% 0px' })
    document.querySelectorAll('.reveal').forEach((el) => observer.observe(el))
    return () => observer.disconnect()
  }, [])
  return (
    <div className="marketing-page">
      <SiteHeader />
      <main>
        <Hero />
        <Manifesto />
        <Workflow />
        <Everywhere />
        <Integrations />
        <OpenSource />
        <FAQ />
        <FinalCTA />
      </main>
    </div>
  )
}
