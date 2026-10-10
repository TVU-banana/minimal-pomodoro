# Scoped session queries and codebase style invariants

## Context

As the user accumulates timer sessions over months and years, full-table queries become a critical
scalability and memory liability. Previously, daily progress components loaded all sessions from
Room and performed filtering in Kotlin memory. Concurrently, style drift across rapid prototyping
introduced inconsistent import sorting, wildcard imports, and lines exceeding the 100-character
limit specified in `AGENTS.md`.

## Decision

1. **Scoped Room Observation**:
   - `SessionDao` must provide indexed, bounded query APIs (`observeInRange(fromUtcMs, toUtcMs)`)
     for any UI component displaying bounded periods (e.g., today's progress, weekly summaries).
   - Scoped loaders must listen to clock / date rollovers (e.g., `todayRangeFlow`) and dynamically
     switch the bounded range without reading outside the active window.
   - `observeAll()` is reserved strictly for full-database exports and backup mergers.

2. **Strict Codebase Style Invariants**:
   - Every Kotlin line must stay at or below 100 characters unless specifically permitted by the
     style guide (e.g. single-string SQL migration queries).
   - All imports must follow strict ASCII sorting without wildcard imports (`import .*`).
   - App-internal APIs must default to `internal` or `private`.
   - User-visible text and locale format strings must reside in Android XML resources (`strings.xml`)
     rather than Kotlin source code.

## Consequences

- Daily progress observation memory cost is reduced from $O(N_{\text{total}})$ to $O(N_{\text{today}})$.
- Database writes for historical or archived sessions will not trigger useless recalculations in
  active daily progress observers.
- CI and local pre-commit verification require `./gradlew test` and `./gradlew lintDebug` to pass
  with 0 errors and 0 style deviations.
- UI and UX behavior remain 100% byte-for-byte identical.
