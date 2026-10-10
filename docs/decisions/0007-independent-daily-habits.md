# ADR 0007: Independent daily habits

Status: Accepted, 2026-10-04

## Decision

Daily habits are a fourth main destination between Timer and Reports. Habit definitions and local
calendar check-ins share Clock's Room database and backup infrastructure but have no foreign key,
command, progress or lifecycle relationship with timer Tags, presets or Session. Habit commands
use HabitRepository; TimerCoordinator remains exclusive to timers.

## Calendar and persistence

Each habit is binary and daily. Creation starts on the device's current LocalDate. Names are literal
text, including any count hints, with no inferred rules. Past eligible days support completion and
undo; future days fail validation. The composite key is `(habitId, localDateIso)`. Completion UTC
timestamps serve auditing only and never move a record between calendar days.

Creation and archive dates are inclusive historical boundaries. Archived habits disappear from
today's list immediately; on subsequent days their archive-day records remain visible and editable
in history. Habits are absent before their creation date, including active and archived definitions.
Archiving never deletes rows. Undo preserves an explicit false row with a monotonic revision so an
older backup cannot resurrect completion. No streak, reminders or periodic database writes exist.

Every mutation performs validation and write in one Room transaction. Accepted UI writes use a
process-owned coroutine scope so tab replacement does not cancel them. Editor save work is retained
across configuration changes, without retaining the old views. Room Flow refreshes displayed state.
Foreground, date/time/timezone broadcasts and a single next-midnight UI wakeup refresh calendar
selection; no service or additional permission is required.

## Compatibility

Migration 7→8 creates only `habits`, `habit_check_ins` and its date index. Existing migration steps
remain registered. Open backup v3 contains `habits.json` and `habit_check_ins.ndjson`; v1/v2 imports
provide empty habit collections and leave local habits intact when merged. Export uses a transactional
snapshot. Parsing, checksum and reference validation complete before the import transaction writes.

Habit definitions merge by ID and updated timestamp; check-ins merge by composite key and updated
timestamp. Equal revisions retain the local value. Creation metadata is immutable for a stable ID:
conflicting creation metadata rejects the whole import. Archive-day or timezone-shifted records
remain valid backup content even when not currently editable. Session CSV remains unchanged.

## Presentation

The existing neutral theme tokens, system type, fine separators and outline icons are reused.
A seven-day selector scrolls on small widths without reducing 48dp targets. Short landscape windows
with enlarged text use the date picker and range arrows, leaving room for full-size list actions.
Day selection, outlined/checked icons and native checked accessibility semantics supplement color.
Rows show only the name, with the check and menu centered vertically; no redundant status subtitle.
Names wrap; the row menu exposes rename, ordering and archive. Both themes and enlarged text require
running Android verification.
