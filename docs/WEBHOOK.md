# Velum outbound webhook

Generic HTTPS webhook for Zapier, n8n, Make, or any HTTP automation tool.
No Velum cloud account — the user pastes a URL (and optional HMAC secret) under
**Settings → Integrations**.

## Configuration

| Field | Storage | Notes |
|---|---|---|
| URL | Android Keystore / secure store | `https://…` required; `http://localhost` / `127.0.0.1` allowed for local n8n |
| HMAC secret | Android Keystore / secure store | Optional; never logged |

AutoFlow post-action **Post webhook** appears only after a URL is saved.

## Payload schema (`schema_version`: 1)

```json
{
  "schema_version": 1,
  "recording_id": "rec-…",
  "title": "Weekly sync",
  "started_at": 1729939155,
  "duration_seconds": 3600,
  "source": "phone",
  "speakers": ["Ada", "Bob"],
  "tags": ["velum"],
  "transcript": "…",
  "summary_markdown": "## Overview\n- …"
}
```

- `Content-Type: application/json`
- Optional header: `X-Velum-Signature: sha256=<hex>` where `<hex>` is
  HMAC-SHA256 of the raw UTF-8 body with the user secret
- Retries: up to 3 attempts with exponential backoff (400 ms, 800 ms)

## Verify signature (Node / n8n Function)

```js
const crypto = require("crypto");
const secret = $env.VELUM_WEBHOOK_SECRET;
const body = $json.bodyRaw; // exact request body string
const expected =
  "sha256=" + crypto.createHmac("sha256", secret).update(body, "utf8").digest("hex");
const ok = expected === $request.headers["x-velum-signature"];
```

## Sample n8n flow

1. **Webhook** node (POST) → Production URL → paste into Velum Integrations.
2. Optional **Function** node: verify `X-Velum-Signature` as above.
3. **Set** / **Notion** / **Obsidian** node: map `title`, `summary_markdown`, `transcript`.

## Sample Zapier flow

1. **Webhooks by Zapier → Catch Hook** → copy URL into Velum.
2. Map JSON fields (`title`, `summary_markdown`, …) into Notion / Google Docs / Slack.
3. For HMAC: use Zapier Code step with the same `crypto.createHmac` check.

## Obsidian / local folder (related)

For vault export without HTTP, use **Integrations → Local folder / Obsidian**
(SAF picker). AutoFlow action **Export to folder** writes Markdown + YAML frontmatter.
See `MarkdownNoteExport` in `:core`.
