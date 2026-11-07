## Database Migrations

### Reversible
Always implement rollback methods for safe migration reversals.

### Small and Focused
Keep each migration to a single logical change.

### Zero-Downtime Awareness
Consider deployment order and backward compatibility for high-availability systems.

### Separate Schema and Data
Keep schema changes separate from data migrations for safer rollbacks.

### Careful Indexing
Create indexes on large tables carefully, using concurrent options when available.

### Descriptive Names
Use names that indicate what the migration does.

### Version Control
Commit migrations; never modify existing ones after deployment.

### Liquibase File Naming and Codegen
Migrations are Liquibase YAML files at `src/main/resources/db/changelog/2026/NNN-<verb>-<description>.yaml` with a zero-padded sequence number (e.g. `011-create-footprint-audit-log.yaml`), picked up by `includeAll` from `db.changelog-master.yaml`. Sample data goes in changesets with the `dev` context. After any migration change, run a normal Maven build (without `-Dskip.jooq.generation`) so jOOQ classes are regenerated.

Source: CLAUDE.md, src/main/resources/db/changelog/db.changelog-master.yaml, src/main/resources/db/changelog/2026/
