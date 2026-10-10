# Daily habit repetitions

## Behavior

Each habit has a daily target from 1 to 99. A tap records one repetition until the target is reached.
Only then does the existing circle become checked. A checked tap removes exactly one repetition;
the row menu can undo one repetition at any intermediate state. Single-count habits keep their
existing appearance. The list never reorders when progress changes.

The name uses 17sp text. A 13sp remaining number follows it with a 12dp gap, using the existing
muted semantic color. There is no multiplication sign, count badge, or status subtitle. The number
is hidden after completion. The title group retains natural width for short names and reserves
space for the number when names wrap. All action targets remain at least 48dp.

The editor places a labeled target with decrement, numeric input, and increment below the name.
After recording, a transient progress message exposes a visible undo action for five seconds,
extended when requested by Android accessibility settings. A delayed feedback undo is bound to the
accepted check-in revision, so it cannot remove a later repetition. The row menu remains available
when the transient message is gone. Changing the selected date hides feedback for the previous day.

## Storage and compatibility

Room remains authoritative. Migration 9 to 10 adds completedCount and habit_targets. Legacy true
rows become one repetition; false rows become zero. Missing target history defaults to one.
The target table is keyed by habit and effective local date, including days without check-ins.
Changing the target affects today and subsequent days while preserving today's count and all
previous dates. Decreasing a target preserves already-recorded repetitions even above the new goal.
Each new day starts at zero without a scheduled reset or background worker.

Commands validate dates and read-modify-write counts within one Room transaction. Accepted commands
use the existing application command scope, so navigation and theme recreation do not cancel writes.
The completed field is a compatibility cache; displayed completion is derived from counts and the
applicable date-effective target. Zero-count undo rows retain their newer revision for backup merges.

Open backup v4 includes counts and habit_targets.json, with stable ordering, validation, and checksums.
Imports remain compatible with versions 1, 2, and 3. Target and count revisions merge independently;
state is calculated from the resulting target and count. No timer, alarm, Session, or CSV contract
changes are required. No network access, dependency, service, or per-second writes are introduced.
