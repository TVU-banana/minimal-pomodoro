# Clock engineering rules

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
