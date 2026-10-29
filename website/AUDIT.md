# Audit: src

## Surfaces

One row per screen. **Fill this in before fixing anything.** Classifying
after changing things is justifying what was already done.

- **A acquisition**: whoever is not a customer yet. Persuade, prove, convert.
- **B work**: whoever uses it every day. Speed, density, keyboard.
- **C trust**: money, permission, destructive action. It cannot lie.

Applying A instincts to a B screen is the most expensive Visant frontend mistake.

| Route | File | Surface (A/B/C) | Business variable it moves | Shell | Notes |
|---|---|---|---|---|---|
| / | src/Marketing.tsx | A | GitHub visits, documentation starts, product-qualified signups | Shared glass header and footer | Prove cross-device capture and open device compatibility without unsupported sync claims |
| /docs/:slug | src/Docs.tsx | B | Time to first successful recording and documentation completion | Documentation sidebar, search, previous/next | Make setup, recorder import, privacy, and contribution paths fast and verifiable |

Redirects (no screen of their own, not in the table): `/docs` goes to `/docs/introduction`, `*` goes to `/`.

Routes read from `src/App.tsx`.

## Screen seen

One row per screen OPENED, with what was seen. Without this the gate does not
open: a frozen AnimatePresence passes tsc, ESLint, motion-lint, impeccable,
audit:design and copy-lint without lighting anything up.

Each row carries: route, width, data state, and what happened.
Before measuring anything, check that the page hydrated (see
`visant-killer/references/environment.md`): half the findings of one session
were the dev server lying, not the screen.

- `/` at 360, 768, 1024, 1440, and 1920 px with rich product-demo imagery: every section opened, images loaded, no horizontal overflow; keyboard tabs and mobile menu verified.
- `/docs/introduction` at desktop and mobile with populated guides: navigation and search opened; recorder result routed correctly.
- `/docs/recorders` at desktop with supported-device content: direct documentation search destination rendered.

## Findings

### Fixed

- Added one motion source of truth for CSS and Framer Motion. Removed height and width animation from the FAQ and navigation underline.
- Replaced text arrows with library icons and cleaned all confirmed copy tics across marketing, documentation, and metadata.
- Added `public/robots.txt`. A sitemap still needs the final production origin; no domain was invented for this report.
- Isolated the browser test server from the local preview.

### Verified

- `motion-lint`: clean.
- `copy-lint`: clean.
- Killer AI-tell detector: zero findings.
- Production build: passed.
- Browser and accessibility tests: 9/9 passed at 360, 768, 1024, 1440, and 1920 px.

### Deliberately retained

- Editorial section numbers and the existing light-glass marketing palette remain. The generic token fallback still reports raw color declarations and numbered labels, so the combined generic gate is not reported as open.
- Instrument Serif remains as a deliberate brand counterpoint. The font warning is a generic heuristic, not a measured design failure.
