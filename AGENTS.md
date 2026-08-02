# Clock engineering rules

## Communication

- Keep every user-facing response concise, high-density, and focused on actionable information.
- Lead with the outcome, decision, or blocker. Omit routine narration, repeated context, generic
  background, exhaustive logs, and low-value implementation detail unless the user requests it.
- Prefer a short paragraph, compact list, or table over a long explanation. Add detail only when it
  materially improves correctness, verification, or the user's next decision.
- Keep progress updates brief and report only material findings, assumptions, changes, or blockers.
- Use Mermaid when relationships, hierarchy, branching, or a multi-step flow become materially
  easier to understand visually. Do not add a diagram for a simple fact, list, or linear instruction.
- Keep diagrams minimal and accurate. Accompany them with only the prose needed to interpret or act
  on them.

## Kotlin style

- Follow the Google Android Kotlin Style Guide.
- Run Android Lint and the complete test suite before committing.
- Keep lines at or below 100 characters unless the style guide explicitly permits an exception.
- Keep imports ASCII-sorted and never use wildcard imports.
- Prefer the narrowest practical visibility. App-internal APIs should normally be `internal`.

## Documentation and comments

- Write all KDoc and code comments in English.
- Keep standard technical terms in English, including Room, AlarmManager, StateFlow, UTC,
  elapsedRealtime, R8, SHA-256, and NDJSON.
- Add KDoc to public Android entry points and internal contracts whose lifecycle, invariants,
  failure behavior, threading, persistence, or compatibility rules are not obvious.
- Comments must explain why a constraint exists. Do not narrate obvious statements.
- Document state-machine invariants, transaction boundaries, alarm idempotency, recovery behavior,
  backup compatibility, and rendering hot-path restrictions.

## Architecture

- The timer reducer must remain independent of Android APIs and deterministic under injected time
  and identifier sources.
- Room is the source of truth. UI and receivers must dispatch commands through TimerCoordinator.
- User-visible text belongs in Android resources, not Kotlin source.
- Keep Android lifecycle code thin. Move calculations, persistence, and rendering orchestration into
  focused collaborators with unit-testable interfaces.
- Do not add network access, persistent services, reflection-based dependency injection, or
  per-second database writes.
