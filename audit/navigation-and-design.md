# Navigation, goal calendar and design pass (23 Nov 2026, Claude 1)

Source before this work: `audit/backup-2026-11-23/app-src-before-nav.tgz`.

## Navigation

Five tabs: Today, Calendar, Health, Trends, You. Pages opened from a tab sit on top of it with a back arrow, and the tab stays highlighted.

| ID | Problem | Cause | Fix |
|---|---|---|---|
| N-1 | Today showed the last opened page again | Tab taps saved the stack and restored it (`saveState` / `restoreState`) | A tab tap always closes opened pages; nothing is saved to be put back |
| N-2 | Pages opened from Today changed the selected tab | Heart, Sleep and Activity were both tabs and detail pages | They are detail pages now; the bar keeps the tab they were opened from |
| N-3 | Settings only reachable through the sync chip | No place for it | You tab (account, goals, Today screen, sync and data, appearance, units) |

## Goals and calendar

- New daily calorie goal (`Goals.dailyCalories`, 2,800 by default; all energy used in a day).
- Goal history (`DatedGoals`, `SettingsStore.goalHistory`): a changed goal counts from that day; earlier days keep theirs.
- `ui/goals/GoalCalendar` (pure, tested): progress per day, "All goals" = steps + sleep + calories + zone minutes, streaks (an unfinished today never breaks one), month grid.
- Calendar tab, day page, and one detail page for every metric (week, month, year).

Data-layer files touched (not committed): `domain/Models.kt`, `domain/HealthRepository.kt`, `data/HealthRepositoryImpl.kt`,
`data/local/SettingsStore.kt`, `sync/GisTokenProvider.kt` (message text only). No database change.

## Design

| ID | Problem | Fix |
|---|---|---|
| V-1 | Android's default purple on switches and buttons, both themes | Every theme colour is named |
| V-2 | Cards in a row had different heights | TileRow: all as tall as the tallest |
| V-3 | Numbers and units wrapped or stood vertically | BigValue: one line, shrinks to fit |
| V-4 | Goal labels drawn over bars | Goal in the card header; dashed line only |
| V-5 | Lines had no scale; flat lines stuck to an edge | Scale on the right, whole numbers, some air |
| V-6 | Bedtime chart showed nothing useful | BedtimeChart with times |
| V-7 | Every screen had its own header | TabHeader and DetailPage |
| V-8 | Monospace capital labels, mixed spacing | One type scale, one spacing system |
| V-9 | REM too dim in dark; steps, calories and zone minutes all one colour | Own colours for REM, calories, zone minutes |
| V-10 | Welcome screen cut off with large text | Scrolls when needed |

## Checked

Emulator (Android API 37): every screen in light and dark, 150% text, landscape on Calendar, empty (no data) state, and a scripted
navigation run. Real data on a real phone: not checked.
