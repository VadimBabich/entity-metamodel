# Security Policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately via GitHub's security advisories:
**[Report a vulnerability](../../security/advisories/new)** (Security tab → Report a
vulnerability). Do not open a public issue for a suspected vulnerability.

You can expect an initial response within **14 days**.

## Supported versions

Honest status, so expectations are right:

| Version | Status |
|---|---|
| 1.0.0 | Abandoned — do not use; it will not receive fixes. |
| 1.x | Retired and removed from the repository. No fixes. |
| 2.0.0 (planned) | Built, not released. Fixes will land here once it ships. |

## Scope notes

The annotation processor runs at build time: it reads Java sources, executes none of the code it
reads, and performs no network I/O. Reports about that path (e.g.
crafted sources causing pathological behavior) are in scope.

The runtime artifacts run inside the consumer's application. `entity-metamodel-runtime-r2dbc`
builds SQL from metamodel references and executes it against the consumer's database, so reports
about generated SQL — including the `RawSql`/`SqlExpr` escape hatch — are in scope. Database
credentials, connection management and the database itself belong to the consumer.
