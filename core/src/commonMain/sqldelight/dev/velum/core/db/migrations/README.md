# VelumDatabase migrations

Current schema version: **1** (defined by `Velum.sq`; see generated `VelumDatabase.Schema.version`).

## Next schema change

1. Add `1.sqm` in this directory (or alongside `Velum.sq`) with the ALTER/CREATE statements that migrate version 1 → 2.
2. Update `Velum.sq` to the new shape.
3. Set `verifyMigrations.set(true)` in `core/build.gradle.kts` once you have a `.sqm`.
4. Ship only forward migrations — do not rewrite prior `.sqm` files after release.

Empty / no-op migrations are fine for tooling dry-runs; never drop user data without a backup path.
