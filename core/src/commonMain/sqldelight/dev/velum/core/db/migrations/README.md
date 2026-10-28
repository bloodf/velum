# VelumDatabase migrations

Current schema version: **4** (`Velum.sq` + `1.sqm` 1→2 + `2.sqm` 2→3 + `3.sqm` 3→4; see generated `VelumDatabase.Schema.version`).

Verification snapshots:
- `src/commonMain/sqldelight/databases/1.db` (pre-`featured` schema) so `verifyMigrations` can apply `1.sqm`.
- `src/commonMain/sqldelight/databases/2.db` (with `featured`, before tags/lang/preferred model columns) so `verifyMigrations` can apply `2.sqm`.
- `src/commonMain/sqldelight/databases/3.db` (tags/lang/preferred model, before `summary_version`) so `verifyMigrations` can apply `3.sqm`.

## Next schema change

1. Add `4.sqm` in this directory with the ALTER/CREATE statements that migrate version 4 → 5.
2. Update `Velum.sq` to the new shape.
3. Drop a `databases/4.db` snapshot of the version-4 schema (or regenerate via SQLDelight) before enabling verification for that step.
4. Keep `verifyMigrations.set(true)` in `core/build.gradle.kts`.
5. Ship only forward migrations — do not rewrite prior `.sqm` files after release.

Empty / no-op migrations are fine for tooling dry-runs; never drop user data without a backup path.
