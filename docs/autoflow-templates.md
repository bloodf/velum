# AutoFlow automation templates

Ready-made AutoFlow rules users can browse in the app (**Modelos de automação** / **Automation templates**).

Each template is a concrete AutoFlow definition using only capabilities that work today:

| Piece | What works |
|---|---|
| Trigger | New recording appears after AutoFlow starts |
| Conditions | Source (`note_pro` / `phone` / `import` / `all`), duration range, optional wake phrase in title/transcript |
| Actions | Always transcribe + summarize with a note `template_id`; optional single post-action: none / share / calendar / folder export / webhook |

## Layout

```text
templates/autoflow/
  schema/autoflow-template.schema.json   # versioned JSON Schema (schema_version = 1)
  <category>/<slug>.json                 # one file per template
```

Packed at sync time into:

```text
core/src/commonMain/resources/autoflow_templates.jsonl
```

## Add a template

1. Create `templates/autoflow/<category>/<slug>.json` (category folder must match the `category` field).
2. Fill both `en` and `pt-BR` for `name`, `description`, and `preview`.
3. Reference a real note-template id (prefer stable `velum.*` ids from `BuiltinTemplateCatalog`).
4. Use only allowed `source_filter`, `language`, and `post_action` values from the schema.
5. For community contributions, set `author: { "name": "…", "github": "handle" }`. Built-in Velum templates omit `author`.
6. Validate and pack:

```bash
./scripts/sync-autoflow-templates.sh
# or
./gradlew :core:validateAutoflowTemplates
./gradlew :core:jvmTest
```

7. Open a pull request (or, for maintainers, push to `main` when CI is green).

### Minimal example

```json
{
  "schema_version": 1,
  "id": "meetings.example-standup",
  "category": "meetings",
  "icon": "meeting",
  "name": {
    "en": "Example standup",
    "pt-BR": "Standup de exemplo"
  },
  "description": {
    "en": "Short standups into extractive notes.",
    "pt-BR": "Standups curtos em notas extrativas."
  },
  "preview": {
    "en": "When a short recording titled with “standup” arrives, extract key items.",
    "pt-BR": "Quando uma gravação curta com “standup” no título chegar, extrair itens-chave."
  },
  "author": { "name": "Ada Lovelace", "github": "ada" },
  "definition": {
    "wake_phrase": "standup",
    "source_filter": "all",
    "duration_min_seconds": 60,
    "duration_max_seconds": 1200,
    "template_id": "velum.extractive",
    "auto_label_voice": true,
    "language": "auto",
    "notify": true,
    "post_action": null
  }
}
```

## Categories

`meetings`, `sales`, `interviews`, `medical`, `legal`, `education`, `personal`, `productivity`, `research`, `content`, `real_estate`, `finance`, `it`, `devices`

## Validation

`scripts/sync-autoflow-templates.sh` (Gradle task `:core:validateAutoflowTemplates`) checks:

- JSON parses and `schema_version` is `1`
- Unique `id`s; folder matches `category`
- Both locales present
- Allowed enums for category / icon / source / language / post-action
- `template_id` exists in the bundled note-template catalogs
- At least 100 templates packed

`:core:jvmTest` depends on the validator and also loads the classpath catalog in `AutoFlowTemplateCatalogTest`.

## App behavior

- Browse: category chips, search, plain-language preview, optional author credit.
- Pick → opens the AutoFlow editor pre-filled from the template.
- If the post-action needs an export folder or webhook URL, save asks clearly and links to Integrations.
