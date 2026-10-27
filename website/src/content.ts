export type DocBlock =
  | { type: 'paragraph'; text: string }
  | { type: 'heading'; text: string; id: string }
  | { type: 'list'; items: string[]; ordered?: boolean }
  | { type: 'code'; language: string; code: string }
  | { type: 'callout'; title: string; text: string; tone?: 'note' | 'warning' }
  | { type: 'table'; headers: string[]; rows: string[][] }

export type DocEntry = {
  slug: string
  title: string
  description: string
  section: string
  readTime: string
  keywords: string[]
  blocks: DocBlock[]
}

export const docs: DocEntry[] = [
  {
    slug: 'introduction',
    title: 'Meet Velum',
    description: 'An open home for everything you hear, say, and want to remember.',
    section: 'Start here',
    readTime: '3 min',
    keywords: ['overview', 'open source', 'ai notetaker', 'desktop', 'mobile'],
    blocks: [
      { type: 'paragraph', text: 'Velum is an open-source AI notetaker being built for desktop and mobile. It gives recordings, transcripts, and generated notes one consistent place to live—whether a conversation begins in an app, on your computer, or on a dedicated AI recorder.' },
      { type: 'callout', title: 'Built in the open', text: 'Velum is under active development. Interfaces and capabilities can evolve between releases; the project roadmap is the best place to follow what is landing next.' },
      { type: 'heading', text: 'Why Velum exists', id: 'why-velum-exists' },
      { type: 'paragraph', text: 'A recording should not become another forgotten file. Velum is designed around the path from capturing a conversation to returning to its meaning: the decision, the follow-up, the quote, or the small detail you need next week.' },
      { type: 'list', items: ['Capture conversations from desktop, mobile, and compatible recorder workflows.', 'Keep audio, transcripts, and notes connected instead of scattered across tools.', 'Find past conversations through familiar search and AI-assisted discovery.', 'Use an open-source foundation that you can inspect, extend, and help shape.'] },
      { type: 'heading', text: 'The Velum model', id: 'the-velum-model' },
      { type: 'paragraph', text: 'Every conversation moves through a simple, understandable structure. You stay in control of the source material while Velum makes it more useful.' },
      { type: 'table', headers: ['Layer', 'What it holds'], rows: [['Recording', 'Original audio, duration, capture time, and source context.'], ['Transcript', 'Timestamped text with speaker context when available.'], ['Note', 'A structured view of key points, decisions, and action items.'], ['Library', 'Searchable conversation history across your devices and imports.']] },
      { type: 'heading', text: 'Choose your starting point', id: 'choose-your-starting-point' },
      { type: 'list', items: ['New to Velum: follow the Quick start.', 'Using an AI notetaker or external recorder: read AI notetakers & recorders.', 'Building or self-hosting: open Contributing for the development workflow.'] },
    ],
  },
  {
    slug: 'quick-start',
    title: 'Quick start',
    description: 'Go from a new workspace to your first useful note.',
    section: 'Start here',
    readTime: '5 min',
    keywords: ['install', 'setup', 'first recording', 'workspace'],
    blocks: [
      { type: 'paragraph', text: 'This guide follows the core Velum workflow. The exact controls may vary by release and platform, but the capture → transcript → note flow stays the same.' },
      { type: 'heading', text: '1. Get the app', id: 'get-the-app' },
      { type: 'paragraph', text: 'Choose the desktop or mobile build for your platform from the project releases. For desktop development, use the repository instructions that accompany the current release.' },
      { type: 'code', language: 'shell', code: 'git clone https://github.com/bloodf/velum.git\ncd velum' },
      { type: 'heading', text: '2. Create your workspace', id: 'create-your-workspace' },
      { type: 'paragraph', text: 'Open Velum and complete the initial workspace setup. Review transcription and AI provider settings before importing sensitive conversations. You can change these preferences later.' },
      { type: 'heading', text: '3. Add a conversation', id: 'add-a-conversation' },
      { type: 'list', items: ['Select New recording to capture directly in the app.', 'Select Import to add an existing audio file.', 'To bring in a hardware recorder, export from its companion app first, then import the file into Velum.'], ordered: true },
      { type: 'heading', text: '4. Generate and review the note', id: 'generate-and-review' },
      { type: 'paragraph', text: 'Start transcription once the recording is ready. When processing finishes, review speaker names and important passages before generating a structured note. AI output is a draft: confirm decisions, names, dates, and action items against the source.' },
      { type: 'callout', title: 'Keep the source close', text: 'Use transcript timestamps to jump back to the relevant part of the recording whenever a detail needs verification.' },
      { type: 'heading', text: '5. Find it again', id: 'find-it-again' },
      { type: 'paragraph', text: 'Search by a person, phrase, meeting title, or topic. Open a result to see its note, transcript, and recording together.' },
    ],
  },
  {
    slug: 'desktop',
    title: 'Desktop app',
    description: 'Capture and work through conversations at your desk.',
    section: 'Apps',
    readTime: '4 min',
    keywords: ['macos', 'windows', 'linux', 'desktop', 'recording'],
    blocks: [
      { type: 'paragraph', text: 'The desktop experience is designed for meetings, interviews, lectures, and focused review. It brings capture and a comfortable reading workspace into the same flow.' },
      { type: 'heading', text: 'Recording on desktop', id: 'recording-on-desktop' },
      { type: 'paragraph', text: 'Before your first capture, check the selected input and confirm that your operating system has granted microphone access. Use a short test recording to verify levels and transcription quality.' },
      { type: 'list', items: ['Open System Settings → Privacy & Security → Microphone on macOS.', 'Open Settings → Privacy & security → Microphone on Windows.', 'Choose the intended microphone in Velum before starting.'] },
      { type: 'heading', text: 'The review workspace', id: 'review-workspace' },
      { type: 'paragraph', text: 'A conversation can be read as a note or a transcript without losing its connection to the audio. Use the transcript for precise context and the note for a faster overview.' },
      { type: 'table', headers: ['View', 'Best for'], rows: [['Note', 'Summaries, decisions, action items, and quick review.'], ['Transcript', 'Speaker-level context and timestamped passages.'], ['Audio', 'Verifying tone, wording, and details in the original recording.']] },
      { type: 'heading', text: 'Good capture habits', id: 'good-capture-habits' },
      { type: 'list', items: ['Place the microphone close to the primary speakers.', 'Reduce constant background noise where possible.', 'Rename conversations soon after capture.', 'Ask for consent before recording other people.'] },
    ],
  },
  {
    slug: 'mobile',
    title: 'Mobile app',
    description: 'Keep your conversation library within reach.',
    section: 'Apps',
    readTime: '4 min',
    keywords: ['ios', 'android', 'mobile', 'capture', 'sync'],
    blocks: [
      { type: 'paragraph', text: 'Velum mobile is designed for conversations away from your desk: a quick voice note, an in-person interview, or reviewing a meeting on the way to the next one.' },
      { type: 'heading', text: 'Capture on the go', id: 'capture-on-the-go' },
      { type: 'paragraph', text: 'Allow microphone access when prompted, then start a new recording from the main capture control. Keep the app open and the device unlocked if your platform or release requires it.' },
      { type: 'callout', title: 'Respect the room', text: 'Recording laws and expectations vary. Let participants know when you are recording and follow the rules that apply to your location and organization.', tone: 'warning' },
      { type: 'heading', text: 'Import from another app', id: 'import-from-another-app' },
      { type: 'list', items: ['Export or share the audio from your recorder’s companion app.', 'Choose Velum as the destination when it appears in the system share sheet.', 'Alternatively, save the file and use Import inside Velum.', 'Add a title and source details so the conversation is easier to find.'], ordered: true },
      { type: 'heading', text: 'Review anywhere', id: 'review-anywhere' },
      { type: 'paragraph', text: 'Open a conversation to move between its note and transcript. Search remains the fastest route when you remember only a topic or a distinctive phrase.' },
    ],
  },
  {
    slug: 'recorders',
    title: 'AI notetakers & recorders',
    description: 'Use supported AI notetakers and external audio recorders with Velum.',
    section: 'Capture & understand',
    readTime: '6 min',
    keywords: ['plaud', 'bee', 'limitless', 'omi', 'soundcore work', 'iflytek', 'external recorder', 'hardware', 'import', 'export'],
    blocks: [
      { type: 'paragraph', text: 'Velum supports dedicated AI notetakers and external audio recorders, giving conversations from the devices you already use one open home. Audio, available transcripts, and generated notes stay connected in your Velum library.' },
      { type: 'heading', text: 'Supported AI notetakers', id: 'supported-ai-notetakers' },
      { type: 'list', items: ['Plaud', 'Bee', 'Limitless', 'Omi', 'Soundcore Work', 'iFLYTEK devices'] },
      { type: 'callout', title: 'Flexible device workflows', text: 'Available import options and formats depend on the device, its companion app, and the Velum release. Use the export or sharing options provided by your specific device.' },
      { type: 'heading', text: 'Import from an AI notetaker', id: 'import-from-an-ai-notetaker' },
      { type: 'list', items: ['Finish the recording and allow the device’s companion app to process it when required.', 'Export or share the original audio. Include an available transcript if you want to preserve the device’s text output.', 'In Velum, choose Import and select the recording.', 'Confirm the title, recording date, language, and any available speaker context.', 'Run transcription or note generation only for the layers you want Velum to create.'], ordered: true },
      { type: 'heading', text: 'External audio recorders', id: 'external-audio-recorders' },
      { type: 'paragraph', text: 'Velum also supports external audio recorders. Transfer the highest-quality source recording available, then add it to Velum through the import workflow. Avoid compressed messaging-app copies when the original file is available.' },
      { type: 'heading', text: 'Choose the best source', id: 'choose-the-best-source' },
      { type: 'table', headers: ['Source', 'Recommendation'], rows: [['Original audio', 'Preferred. Gives you the most flexibility for future transcription.'], ['Compressed audio', 'Useful when original-quality export is unavailable.'], ['Transcript only', 'Keeps text searchable, but no audio verification is possible.'], ['Generated summary', 'Treat as reference material, not a substitute for the conversation.']] },
      { type: 'heading', text: 'Troubleshooting imports', id: 'troubleshooting-imports' },
      { type: 'list', items: ['If a file is rejected, check its extension and export a common audio format from the source device or companion app.', 'If speech is missing, listen to the exported file before reprocessing it in Velum.', 'If timestamps differ, verify that the audio and transcript came from the same recording.', 'If speaker labels are wrong, correct them before relying on generated action items.'] },
    ],
  },
  {
    slug: 'transcripts-notes',
    title: 'Transcripts & notes',
    description: 'Move from an accurate source to a useful, reviewable summary.',
    section: 'Capture & understand',
    readTime: '5 min',
    keywords: ['transcription', 'summary', 'actions', 'speakers', 'notes'],
    blocks: [
      { type: 'paragraph', text: 'The transcript and the note serve different jobs. The transcript preserves the flow of the conversation; the note makes its most important ideas easier to scan.' },
      { type: 'heading', text: 'Improve transcript quality', id: 'improve-transcript-quality' },
      { type: 'list', items: ['Select the correct spoken language before processing.', 'Use the least-compressed source audio available.', 'Provide relevant names or vocabulary when the app supports context hints.', 'Review and correct speaker assignments in multi-person recordings.'] },
      { type: 'heading', text: 'Read a structured note', id: 'read-a-structured-note' },
      { type: 'table', headers: ['Section', 'What to check'], rows: [['Summary', 'Does it represent the conversation’s actual focus?'], ['Key points', 'Are important qualifications or disagreements preserved?'], ['Decisions', 'Was a decision truly made, or only proposed?'], ['Action items', 'Are the owner, task, and timing supported by the audio?']] },
      { type: 'heading', text: 'Review before sharing', id: 'review-before-sharing' },
      { type: 'paragraph', text: 'Generated text can omit context or interpret uncertain speech incorrectly. Verify names, numbers, commitments, and quotations before using a note to make decisions or sharing it with others.' },
      { type: 'callout', title: 'A note is a map, not the territory', text: 'When a passage matters, follow its transcript context and return to the original audio.' },
    ],
  },
  {
    slug: 'search',
    title: 'Search & rediscover',
    description: 'Find the moment you need without remembering the filename.',
    section: 'Capture & understand',
    readTime: '4 min',
    keywords: ['search', 'ask', 'library', 'find', 'history'],
    blocks: [
      { type: 'paragraph', text: 'A useful notetaker is measured by what you can recover later. Velum organizes conversation content around the ways people actually remember: a topic, a voice, a phrase, or a rough point in time.' },
      { type: 'heading', text: 'Search your library', id: 'search-your-library' },
      { type: 'list', items: ['Use a distinctive phrase to locate a transcript passage.', 'Search a project, person, or meeting title to narrow the library.', 'Combine a topic with a date range when your release provides filters.', 'Open the matching passage to recover its surrounding context.'] },
      { type: 'heading', text: 'Ask better questions', id: 'ask-better-questions' },
      { type: 'paragraph', text: 'Where AI-assisted question answering is available, anchor questions in a specific conversation or a clearly defined set of results. Focused questions are easier to verify than broad questions across an entire library.' },
      { type: 'list', items: ['“What decisions were made about the mobile launch?”', '“Which follow-ups were assigned to the design team?”', '“Where did we discuss the recording retention policy?”'] },
      { type: 'heading', text: 'Verify the answer', id: 'verify-the-answer' },
      { type: 'paragraph', text: 'Treat generated answers as navigation aids. Open cited passages or matching transcript results before acting on important information.' },
    ],
  },
  {
    slug: 'privacy',
    title: 'Privacy & data',
    description: 'Understand the questions to ask before capturing a conversation.',
    section: 'Project',
    readTime: '5 min',
    keywords: ['privacy', 'security', 'consent', 'storage', 'providers'],
    blocks: [
      { type: 'paragraph', text: 'Audio can contain highly personal and commercially sensitive information. Velum’s open-source approach lets you inspect the implementation, but responsible capture still depends on your configuration, connected services, and handling practices.' },
      { type: 'callout', title: 'Configuration matters', text: 'Storage, processing location, retention, and third-party data handling depend on the app version and providers you choose. Review the release documentation and your settings before processing sensitive material.', tone: 'warning' },
      { type: 'heading', text: 'Before you record', id: 'before-you-record' },
      { type: 'list', items: ['Get consent from participants and follow applicable recording laws.', 'Confirm whether the conversation is appropriate for AI processing.', 'Review the selected transcription and generation providers.', 'Understand where originals, transcripts, and generated notes are stored.'] },
      { type: 'heading', text: 'Connected providers', id: 'connected-providers' },
      { type: 'paragraph', text: 'If a feature sends content to an external transcription or AI service, that service’s terms and retention practices also apply. Do not assume that an open-source client makes third-party processing local or private.' },
      { type: 'heading', text: 'A practical checklist', id: 'practical-checklist' },
      { type: 'table', headers: ['Question', 'Where to verify'], rows: [['What leaves this device?', 'Provider and processing settings.'], ['How long is content retained?', 'App settings and connected provider policies.'], ['Who can access the workspace?', 'Account, sharing, and device access controls.'], ['Can I remove the source and derived data?', 'Conversation deletion and provider controls.']] },
    ],
  },
  {
    slug: 'contributing',
    title: 'Contributing',
    description: 'Help build an open, thoughtful home for conversations.',
    section: 'Project',
    readTime: '6 min',
    keywords: ['github', 'development', 'issues', 'pull request', 'open source'],
    blocks: [
      { type: 'paragraph', text: 'Velum welcomes contributions that improve capture, review, accessibility, platform support, documentation, and recorder interoperability. Start small, explain the problem, and keep changes focused.' },
      { type: 'heading', text: 'Find a useful first task', id: 'find-a-useful-first-task' },
      { type: 'list', items: ['Browse open issues for confirmed bugs and scoped enhancements.', 'Reproduce a problem with clear platform and release details.', 'Improve a guide when a workflow is unclear or incomplete.', 'Discuss larger architectural changes before implementing them.'] },
      { type: 'heading', text: 'Development workflow', id: 'development-workflow' },
      { type: 'code', language: 'shell', code: 'git clone https://github.com/bloodf/velum.git\ncd velum\ngit checkout -b docs/improve-recorder-guide' },
      { type: 'list', items: ['Fork the repository and create a focused branch.', 'Follow the setup instructions for the app or package you are changing.', 'Add or update checks that demonstrate the intended behavior.', 'Run the relevant build and test commands.', 'Open a pull request with the problem, approach, and verification steps.'], ordered: true },
      { type: 'heading', text: 'A good pull request', id: 'a-good-pull-request' },
      { type: 'paragraph', text: 'Explain the user-facing problem before the implementation. Include screenshots for interface changes, note any platform-specific behavior, and call out trade-offs or follow-up work explicitly.' },
      { type: 'callout', title: 'Do not include real conversations', text: 'Use synthetic audio and redacted examples in issues, tests, and screenshots. Never upload private recordings or credentials.' },
    ],
  },
  {
    slug: 'roadmap',
    title: 'Product principles',
    description: 'The ideas guiding Velum’s desktop, mobile, and recorder workflows.',
    section: 'Project',
    readTime: '3 min',
    keywords: ['roadmap', 'principles', 'future', 'plans'],
    blocks: [
      { type: 'paragraph', text: 'Velum’s direction is intentionally product-led: make captured conversations easier to return to, keep the source connected to every generated layer, and remain open to the recording tools people already use.' },
      { type: 'heading', text: 'Principles over promises', id: 'principles-over-promises' },
      { type: 'table', headers: ['Principle', 'What it means'], rows: [['Source before summary', 'Generated notes should never sever your connection to the recording.'], ['Open by default', 'Development, trade-offs, and extensibility belong in public view.'], ['Bring your own hardware', 'Dedicated recorders should complement the apps, not create another silo.'], ['Cross-platform by design', 'Desktop and mobile should feel related, not like separate products.'], ['Calm intelligence', 'AI should reduce effort without hiding uncertainty or taking control.']] },
      { type: 'heading', text: 'Follow the work', id: 'follow-the-work' },
      { type: 'paragraph', text: 'Use the GitHub repository and issue tracker for the current implementation status. Specific dates and feature commitments belong in release milestones rather than evergreen documentation.' },
      { type: 'heading', text: 'Shape what comes next', id: 'shape-what-comes-next' },
      { type: 'paragraph', text: 'The most useful feedback describes a real workflow: what you recorded, what you needed to recover, and where the current experience created friction. Open a focused discussion or issue with that context.' },
    ],
  },
]

export const docSections = ['Start here', 'Apps', 'Capture & understand', 'Project']

export const getDoc = (slug?: string) => docs.find((doc) => doc.slug === slug)
