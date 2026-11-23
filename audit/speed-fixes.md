# Speed fixes, 23 Nov 2026 (done by Claude 1, approved by the user)

The other session (Claude 2, data layer) was not reachable, so its files were edited on disk and **not committed**.
Its uncommitted work from before these edits is saved in `audit/backup-2026-11-23/` (patch + copies).

| ID | Fix | Where | Checked by |
|---|---|---|---|
| S-1 | Opening Pulse starts a full sync if the last one is over 1 minute old | PulseApp, DashboardViewModel.startLiveRefresh, HealthRepository.refreshIfStale | DashboardViewModelTest |
| S-2 | While Pulse is on screen, today's numbers update every 90 seconds (8 requests) | DashboardViewModel, SyncEngine.refreshToday | DashboardViewModelTest, SyncEngineTest |
| S-3 | Background sync every 15 minutes, no battery-low wait. Old installs move over on the next app start | SyncScheduler.ensurePeriodic | compile + review (needs a phone) |
| S-4 | Today's steps and heart rate first; 3 requests at a time | SyncEngine.syncRecent / parallel | SyncEngineTest (order, 3 at a time) |
| S-5 | One failing type no longer stops the rest (new Outcome.PARTIAL, problem stays visible). History download has its own lock and progress, so it doesn't block refresh or hold back the first numbers | SyncEngine, SettingsStore, SyncStatus.history | SyncEngineTest |
| S-6 | Refresh button on the Smart, Steps, Heart and Sleep widgets (not on the tiny 1x1 sizes or the Sync widget) | PulseWidgets.SyncNowAction, SyncScheduler.syncNow | compile + review (needs a phone) |

## Behaviour changes the data side should know

- `SyncEngine.sync` marks the recent sync successful **before** the history download starts. `isRunning` means a full recent sync only.
- History progress is `SyncStatus.history`; `state` is never "running" because of history.
- An unfinished history download is retried by the regular sync at most once an hour (`historyGap`).
- `exclusive` takes syncLock, backfillLock, detailLock, in that order.
- `SyncEngineTest.unexpectedErrorNeverLeavesRunning` now breaks every type; the one-type case is `oneUnreadableTypeDoesNotStopTheOthers`.

## Known limits

- An interrupted history download starts again from the beginning (as before). Android stops a background job after about 10 minutes.
- Live refresh covers today's totals, heart rate and hourly steps. Sleep, recovery and workouts come with the full sync.
