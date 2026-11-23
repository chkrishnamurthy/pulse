# Data layer and sync audit (Claude 2)

Scope: `domain/**`, `data/**`, `sync/**`, `PulseGraph.kt`, their tests, `gradle/libs.versions.toml`, `app/build.gradle.kts`.
Cross-review of the UI side (`ui/**`, `auth/**`, widgets, manifest) is listed as X-D-### below. Each owner fixes their own files.

Status values: Open, Fixed+verified, Blocked.

## My side

| ID | Area | Severity | Type | Root cause | Expected vs actual | Fix | Status | Verified by |
|---|---|---|---|---|---|---|---|---|
| D-001 | sync/delete | High | Defect | `deleteAllLocalData()` cleared tables while a sync could still be running. | Expected: after "Delete data" / sign-out the database stays empty. Actual: a running sync keeps writing rows (and sets backfill done) after the delete. | `SyncEngine.exclusive{}`: raises an abort flag, waits for the sync and detail locks, then clears. Every write checks the flag; an aborted sync ends quietly as `Outcome.ABORTED` (status Idle). | Fixed+verified | SyncEngineTest.deleteDuringSyncLeavesNothingBehind |
| D-002 | sync/status | High | Defect | Sync state "running" is saved in DataStore. If the process dies mid-sync, nothing resets it. | Expected: Idle after restart. Actual: the spinner shows "Syncing…" until the next sync finishes. | Clear a stale "running" state when the graph starts (one per process, before any sync can run). | Fixed+verified | SyncEngineTest.staleRunningIsCleared |
| D-003 | sync/consent | High | Defect | One failed data type failed the whole sync. Google allows partial consent (the user can untick scopes). | Expected: data for the allowed scopes still syncs. Actual: a 403 on, say, sleep stops steps and heart too. | Each data type is fetched in its own `optional {}` block. 403 (and 400 that isn't ACCOUNT_NOT_LINKED) skips only that type. If every type fails, the first error is reported as before. | Fixed+verified | SyncEngineTest.partialConsentSkipsOnlyThatType, everyTypeRefusedFails |
| D-004 | sync/daily lists | High | Defect | The `{type}.date >= "…"` filter for daily types follows Google's documented pattern, but it's only shown verbatim for daily_heart_rate_variability. | Expected: a rejected filter doesn't block those metrics. Actual: a 400 would fail the sync. | On 400 (not ACCOUNT_NOT_LINKED), retry the same list without a filter. These types have one point per day, so the full list is small. | Fixed+verified | SyncEngineTest.rejectedDailyFilterFallsBackToFullList |
| D-005 | sync/duplicates | Medium | Defect | Sleep and workouts were only upserted. A session deleted or merged at Google stayed in the app forever. | Expected: the local copy matches Google for the window just fetched. Actual: deleted or replaced sessions remained (for example, after editing a sleep in the Google Health app). | `replaceSleepFrom` / `replaceWorkoutsFrom`: delete then insert inside one Room transaction, limited to the exact window that was fetched. | Fixed+verified | SyncEngineTest.syncTwiceMakesNoDuplicates |
| D-006 | data/local | Medium | Defect | The heart-rate day refresh did delete-then-insert without a transaction. | Expected: no empty flash. Actual: the graph could briefly show no data. | `replaceHeartRate` as a `@Transaction`. HealthDao becomes an abstract class to allow it. | Fixed+verified | compile + Room KSP (assembleDebug) |
| D-007 | sync/refresh | Medium | Defect | `refreshNow()` waited on the sync lock. During the first history download, that's minutes. | Expected: pull-to-refresh returns at once (progress already shows on the Sync screen). Actual: the refresh spinner hangs. | If a sync is already running, `refreshNow()` returns right away. | Fixed+verified | code review; HealthRepositoryImpl.refreshNow |
| D-008 | widgets | Medium | Defect | After "Delete data" or sign-out, no sync runs, so the sync listeners never fire. | Expected: widgets show "Open Pulse to sign in". Actual: widgets keep the old numbers until their 1-hour update. | `deleteAllLocalData()` runs the sync listeners after clearing. | Fixed+verified | code review; PulseGraph notifyListeners |
| D-009 | data/remote | Low | Defect | `Retry-After` was used without a limit. | Expected: a bounded wait. Actual: a large header value could stall a worker for a long time. | Cap at 60 s. | Fixed+verified | HealthApiTest (retry suite) |
| D-010 | tests | High | Gap | There were no tests for SyncEngine. Requested: token refused, ACCOUNT_NOT_LINKED, partial history, day boundaries, time zone, DST. | Expected: covered. Actual: none. | `SyncEngineTest` with MockWebServer, an in-memory DAO and a real DataStore. | Fixed+verified | SyncEngineTest (16 tests: token refused, ACCOUNT_NOT_LINKED, partial history, local date vs UTC, DST 25-hour day, chunking) |
| D-011 | release | High | Gap | R8 safety with kotlinx.serialization (`StageRow`), Room and WorkManager was never checked. | Expected: `assembleRelease` passes and the classes survive. | Run assembleRelease and add keep rules if needed. Rules go in proguard-rules.pro, which Claude 1 owns. | Fixed+verified | assembleRelease (R8) passes, 4.3 MB; no extra keep rules needed (kotlinx.serialization, Room, WorkManager ship consumer rules). Runtime check on a device still pending. |
| D-012 | sync/history | Low | Improvement | History discovery stops after 2 empty 90-day windows in a row. | A gap of more than 6 months with no tracker hides older history. Accepted trade-off: it keeps first sync fast and the limit is documented. | None; documented. | Open (accepted) | |
| D-013 | unverified | Medium | Blocked | Nothing has run against the live Google Health API yet. Unverified: paging spelling, the dailyRollUp end-date meaning, which `dataSourceFamily` the default uses, whether background silent `authorize()` works. | Needs the user's sign-in. | Code is defensive: inclusive/exclusive end is harmless thanks to upserts, and failures degrade per type. Re-check with the first real sync. | Blocked (needs user) | |

| D-014 | sync/lint | Low | Defect | Month label formatter cached Locale.getDefault() in a static (lint ConstantLocale). | A language change applies only after restart. | Read the locale each time. | Fixed+verified | lintDebug (warning gone) |
| D-015 | build | Low | Improvement | Minify on without resource shrinking (lint NotShrinkingResources). | Larger release APK. | isShrinkResources = true. | Fixed+verified | assembleRelease |
| D-016 | build | Low | Improvement | Gradle 9.9.2 and Compose BOM 2026.11.01 are available (lint). | Not upgraded on purpose: the app is not yet verified on a device, and upgrading now adds risk. | Revisit after the first real-device run. | Open (deferred) | |

## Fixes from Claude 1's review of my side (X-U, logged in ui-issues.md)

| ID | Result |
|---|---|
| X-U-001 unexpected exceptions left status "running" | Fixed: final catch sets Failed. SyncEngineTest.unexpectedErrorNeverLeavesRunning |
| X-U-002 stale running after process death | Same as D-002. Fixed+verified |
| X-U-003 partial consent / per-type failures | Same as D-003. 403, 404 and 400 (not ACCOUNT_NOT_LINKED) skip one type; 401 and all-types-failed fail. Fixed+verified |
| X-U-004 delete during running sync | Same as D-001. Fixed+verified |
| X-U-005 "internet" text check | Fixed: GisTokenProvider uses Outcome.Failed.retryable (compile verified) |
| X-U-006 sign-out before revoke | No data-side change needed. onSignedOut cancels the workers; deleteAllLocalData aborts a running sync |

## Lint results for Claude 1's files (lintDebug, 09:07)

- **Error** NonObservableLocale: ui/screens/TodayScreen.kt:69 (Locale.getDefault() inside a composable). This error fails lintDebug.
- ConstantLocale: ui/format/Format.kt:15,16; ui/screens/HeartScreen.kt:111; ui/screens/TrendsScreen.kt:121,122.
- UnusedAttribute: targetCellWidth/targetCellHeight in res/xml/widget_*.xml (API 31+ only, harmless; can move to xml-v31).
- DataExtractionRules: AndroidManifest.xml:10 should also set android:fullBackupContent for API 28–30 (allowBackup=false already blocks backup).
- UseKtx: MainActivity.kt:39.

## Cross-review of the UI side (owner: Claude 1)

| ID | Area | Severity | Type | Root cause | Expected vs actual | Suggested fix | Status | Verified by |
|---|---|---|---|---|---|---|---|---|
| X-D-001 | ui/DashboardViewModel | Medium | Defect | `today` is read once when each flow is built (`val d = today` inside `fromRepo{}`). | Expected: after midnight, Today switches to the new day. Actual: with the app left open, Today, Heart and Sleep keep showing yesterday until the ViewModel is recreated. | Drive the date from a flow that ticks at local midnight (and on ACTION_TIMEZONE_CHANGED), and `flatMapLatest` on it. | Open | |
| X-D-002 | ui/sign-out | Medium | Defect | Sign-out runs `dash.deleteLocalData()` (async) and `viewModel.signOut()` in the same click. It works only because `launch` on Main.immediate reads `source.value` before the switch to sample data. | Fragile ordering: if the delete ever reads the source after the switch, the real data isn't deleted. | Capture the real repo before signing out, or let MainViewModel.signOut call `PulseGraph.repository(app).deleteAllLocalData()` and then `onSignedOut`. D-001 makes a concurrent sync safe. | Open | |
| X-D-003 | ui/DashboardViewModel | Low | Defect | `refresh()` / `deleteLocalData()` use `runCatching`, which also swallows CancellationException. | Expected: cancellation propagates. | Catch Exception and rethrow CancellationException. | Open | |
| X-D-004 | ui/Mappers.sleep, dayStats | Low | Defect | The wake day is computed from `end.atZone(systemDefault)`, but the data layer files sleep by Google's civil wake date. | After travel to another time zone, the Sleep screen can pick a body summary (SpO2, skin temp) from the wrong day. | Use the repo's date range (the data layer already filters by wake date) rather than recomputing. | Open | |
| X-D-005 | auth/sign-out | Low | Improvement | Sign-out doesn't revoke Google access. | Expected for privacy: "Sign out" also removes the app's permission (or Settings offers "Disconnect from Google"). Actual: access stays until revoked at myaccount.google.com. | GIS `revokeAccess`, or link to myaccount.google.com/permissions. | Open | |
| X-D-006 | MainViewModel | Low | Improvement | Every app start (silent sign-in) calls `onSignedIn`, which re-enqueues the initial sync, and also runs the identity test. | One extra full "recent" sync and one identity call per app open. Harmless but uses the 300/min quota. | Acceptable. Optionally call onSignedIn only on first sign-in, and rely on the periodic worker plus pull-to-refresh. | Open | |
