# Slack and Notion destinations

Velum can send a note to **Slack** (#61) or **Notion** (#59) without a Velum server or an OAuth app: the user
creates the credential in their own workspace and pastes it under **Settings → Integrations**. Both are optional and
send nothing until configured.

| | Slack | Notion |
|---|---|---|
| Credential | Incoming Webhook URL (`https://hooks.slack.com/services/…`, `/triggers/…` or `/workflows/…`) | Internal integration token (`ntn_…`, older `secret_…`) + database link or id |
| Storage | Secure store (`integrations.slack.webhook_url`) | Secure store (`integrations.notion.token`, `integrations.notion.database_id`) |
| What is sent | Title, date, duration, speakers, **summary** (no transcript) | Title, date, **summary and transcript** as a new database page |
| Manual | Recording → Export → **Post to Slack** (asks first) | Recording → Export → **Export to Notion** (asks first) |
| AutoFlow post-action | `slack` ("Post summary to Slack") | `notion` ("Export to Notion") |
| Cleared by | Integrations → Remove, and **Delete all data** | Integrations → Disconnect, and **Delete all data** |

Saved credentials are never shown again in the app (the fields start empty; the card only says that one is saved),
never logged, and never included in error messages: failures are reported as `HTTP <status>` or a connection code.

## Slack setup

1. In Slack: **Create an app** → *From scratch* → **Incoming Webhooks** → turn on → **Add New Webhook to Workspace**
   → pick the channel.
2. Copy the webhook URL into Velum → Integrations → Slack → **Save**, then **Send test message**.

Message format: Block Kit — a header (title, ≤150 chars), a context line (date · duration · speakers) and the summary
as `mrkdwn` sections (Markdown headings, bold, lists and links are converted; each section ≤3000 chars, at most 50
blocks). Slack returns 403/404/410 for a revoked webhook or archived channel; Velum then asks for a new webhook.
Rate limits (429) and 5xx are retried up to 3 times; other 4xx are not retried.

## Notion setup

1. Go to <https://www.notion.so/my-integrations> → **New integration** (type *Internal*) → copy the token.
2. Open the target database in Notion → **… → Connections → Connect to** → pick the integration.
3. In the database, **… → Copy link**, paste the link and the token into Velum → Integrations → Notion → **Save**.
   Velum checks the connection straight away.

Page format: the page title goes in the database's title column (whatever it is called); if the database has a
**date** column, the first one gets the recording start. The body has a "Summary" heading with the summary and a
"Transcript" heading with the spoken transcript (headings in the app's language). Markdown headings, bulleted /
numbered / to-do lists, quotes, code blocks, bold / italic / code / links are converted; text is split at Notion's
2000-character limit and pages with more than 100 blocks are filled with follow-up append requests.

Errors: 401 → the token was revoked; 404 → the database is not shared with the integration (the most common setup
mistake); a link to a page instead of a database → "not a database". 429 (honouring `Retry-After`) and 5xx are retried
up to 3 times. API version: `Notion-Version: 2022-06-28`.

## AutoFlow

`slack` and `notion` are valid `post_action` values in AutoFlow rules and templates
(`templates/autoflow/schema/autoflow-template.schema.json`). Two built-in templates use them:
`meetings.standup-slack` and `meetings.meeting-notion`. A template whose destination is not configured shows a
"set up … first" dialog instead of saving. The post-action runs as soon as the note is ready, also with no screen
open (`AutoFlowActionRouter`); the result snackbar appears the next time a screen is open.

## Code map

- `core/…/integrations/NoteDestinationSettings.kt` — `SlackSettingsStore`, `SlackWebhookUrl`, `NotionSettingsStore`, `NotionIds`
- `core/…/integrations/SlackMessage.kt`, `NotionBlocks.kt` — payload builders (pure, unit-tested)
- `ai/…/integrations/NoteDestinations.kt` — `SlackPoster`, `NotionExporter`, `DestinationResult`
- `composeApp/…/ui/integrations/NoteDestinationCards.kt` — Integrations cards; `i18n/NoteDestinationStrings.kt` — copy (EN / PT-BR)

## Not done

- OAuth ("Add to Slack" / public Notion integration) — needs an owner-registered client and a redirect handler.
- Choosing a Slack channel per AutoFlow rule (one webhook = one channel), or a Notion database per rule.
- Verified against the real Slack / Notion APIs: the clients are tested against mocked HTTP responses only.
