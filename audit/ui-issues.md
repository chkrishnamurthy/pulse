# Pulse audit: UI, auth, widgets, build (Claude 1)

Scope: `ui/**`, `auth/**`, `MainActivity`, `MainViewModel`, `PulseApplication`, `res/**`, manifest, root build files.
The data layer (`domain/**`, `data/**`, `sync/**`, `PulseGraph`) is audited by Claude 2 in `data-issues.md`.
Baseline: git commit `5f8ec97`.

Severity: Critical / High / Medium / Low. Type: **Defect** (wrong behaviour), **Gap** (missing piece), **Improvement**.

## Defects and gaps in Claude 1's code

| ID | Area | Sev | Type | Root cause | Expected vs actual | Fix | Order | Status | Verified by |
|---|---|---|---|---|---|---|---|---|---|
| U-001 | Auth / sign-out | High | Defect | Sign-out only cleared in-memory state. Google's grant stayed, and `trySilentSignIn()` re-authorized on next launch. | Expected: signed out stays signed out. Actual: next launch silently signs back in and syncs. | Persist a "connected" flag. Sign-out cancels sync, deletes local data, calls `AuthorizationClient.revokeAccess`, clears the flag. Silent sign-in only runs when the flag is set. | P1 | Fixed | compile + MainViewModel review (needs device for revoke) |
| U-002 | Offline / launch | High | Gap | Signed-in state came only from a live GIS call. Offline it fails, so the user lands on Connect with cached data unreachable. | Expected: offline dashboard (brief §7). Actual: Connect screen; "sample data" is the only way in. | The persisted "connected" flag means signed in at once and the real repository is used. The token refresh runs in the background, and a refusal surfaces as "Sign in again". | P1 | Fixed | compile; start-route logic reviewed (device run pending) |
| U-019 | Sign-out ordering | Medium | Defect | UI deleted local data, then signed out, while a sync could still be writing (see X-U-004). | Expected: nothing reappears after sign-out. Actual: a running sync can re-insert rows. | `MainViewModel.signOut()`: onSignedOut (cancel work) → deleteAllLocalData → revoke → clear flag, in order. | P1 | Fixed | compile; ordered suspend calls |
| U-003 | Navigation | Medium | Defect | Tabs used `popUpTo(findStartDestination())` = Connect, which is popped after sign-in, so no-op. "Look around" left Connect under Today. | Expected: Back from any tab goes to Today, then exits. Actual: the back stack grows with every tab tap. | `popUpTo<TodayRoute>`; Connect is always removed when entering Today. | P2 | Fixed | compile (nav runtime check needs device) |
| U-004 | Today / dates | Medium | Defect | `today` and `LocalTime.now()` were captured when the flow was built. | Expected: after midnight, Today shows the new day and the dial's "now" moves. Actual: stuck on the old day until restart. | A minute ticker flow drives the date and time in DashboardViewModel. | P2 | Fixed | DashboardViewModelTest.today rolls over at midnight |
| U-005 | System bars | Medium | Defect | `enableEdgeToEdge()` picks icon colours from the system theme, not the app's Light/Dark override. | Expected: readable status bar in every theme. Actual: light icons on a light background when the app is forced Light on a dark phone (and the reverse). | Re-apply `enableEdgeToEdge(SystemBarStyle…)` whenever the resolved theme changes. Set the window background too (U-014). | P2 | Fixed | compile + lint (visual check needs device) |
| U-006 | Settings / sample mode | Medium | Gap | From sample mode there was no way to connect except restarting the app. | Expected: a Connect button is reachable. Actual: none. | "Connect Google account" in Settings and in the sample banner. | P2 | Fixed | compile |
| U-007 | Error states | Medium | Defect | The Today redesign dropped the connection-test card, so the `ACCOUNT_NOT_LINKED` / 403 explanations were never shown. | Expected: a clear reason when Google refuses. Actual: hidden. | Show the connection-test result in the Settings account card, with "Try again". | P2 | Fixed | compile |
| U-026 | First sync | Medium | Gap | No empty state while the first history download runs. | Expected: "Downloading your history…" with progress. Actual: a page of dashes. | First-sync card on Today when the real repo has no lastSuccess yet. | P2 | Fixed | MappersTest.sync status mapping |
| U-008 | Units | Medium | Defect | Distance on Today and Trends was hard-coded to km. | Expected: the mi setting is respected everywhere. Actual: only Activity converted. | Use `Fmt.distance(…, units)` everywhere. | P3 | Fixed | FormatTest.distance respects units |
| U-009 | Tests | Medium | Gap | No unit tests for UI logic. | Expected: reports, mappers, formatting and widget logic covered. Actual: 0 tests on the UI side. | JUnit tests for Reports, Mappers, Fmt, WidgetDataLoader.smart. | P3 | Fixed | 27 UI unit tests pass |
| U-022 | Lint | Medium | Gap | Android lint never run. | Expected: no lint errors. | Run `lintDebug` and fix errors and real warnings. | P3 | Fixed | lintDebug: 0 errors (2 deferred version warnings, D-016) |
| U-010 | Accessibility | Low | Defect | Reorder buttons were "↑/↓" text with no label. | Expected: TalkBack reads "Move Sleep up". | Add contentDescription. | P3 | Fixed | compile |
| U-011 | Today insight | Low | Defect | The insight dot was always heart-red, even for good news. | Expected: green for good, red for a warning. | Colour by `positive`. | P3 | Fixed | MappersTest.insight |
| U-012 | Trends | Low | Defect | "Best day" showed only a weekday, which is ambiguous over a month or a year. | Expected: "Sat 15 Nov". | Show the date. | P3 | Fixed | compile |
| U-013 | Code quality | Low | Defect | Dead code: `SampleData`, `ComingSoon`, `tileShape`. | | Remove. | P3 | Fixed | compile (SampleData.kt removed) |
| U-014 | Launch flash | Low | Defect | The window background follows the system theme, not the app's override. | Expected: no wrong-colour flash. | Set the window background with the theme (done with U-005). | P2 | Fixed | compile |
| U-015 | Build | Low | Gap | No `.gitattributes`: Windows autocrlf can break `gradlew` (CRLF) on checkout. | | `.gitattributes` with `gradlew eol=lf`, `*.bat eol=crlf`. | P3 | Fixed | .gitattributes added |

## Cross-review findings on Claude 2's code (Claude 2 fixes these; status mirrored from data-issues.md)

| ID | Area | Sev | Type | Root cause | Fix asked for | Status |
|---|---|---|---|---|---|---|
| X-U-001 | SyncEngine | High | Defect | Exceptions other than API/sign-in errors escape `sync()`, so the status stays "running" forever. | Final catch-all → setFailed. | Fixed by Claude 2 (SyncEngineTest; see data-issues.md) |
| X-U-002 | Sync status | High | Defect | "running" is persisted, so process death leaves "Syncing…" on every launch. | Keep running state in memory, or reset on start. | Fixed by Claude 2 (SyncEngineTest; see data-issues.md) |
| X-U-003 | Sync / partial consent | High | Defect | One data type refused (403/400/404) aborts the whole sync. | Per-type skip; fail only on 401 or when every type fails. | Fixed by Claude 2 (SyncEngineTest; see data-issues.md) |
| X-U-004 | Delete during sync | High | Defect | `deleteAllLocalData` races a running sync, which re-inserts data. | Take syncLock / cancel first. | Fixed by Claude 2 (SyncEngineTest; see data-issues.md) |
| X-U-005 | Token provider | Medium | Defect | Network-vs-sign-in decided by matching error text. | Use `Outcome.Failed.retryable` (added by Claude 1). | Fixed by Claude 2 (SyncEngineTest; see data-issues.md) |
| X-U-006 | Sign-out | Medium | Defect | Covered on the UI side by U-001 (revoke). | none | Fixed in U-001 |

## Improvements (not defects). Deferred unless noted

| ID | Item | Why deferred |
|---|---|---|
| I-001 | Bundle the design fonts (Sora, Inter, JetBrains Mono) | User asked to leave design work for later |
| I-002 | Widgets follow the app's Light/Dark override (today they follow the phone) | Design follow-up |
| I-003 | Move UI strings to `strings.xml` for translation | Personal, English-only app; low value now |
| I-004 | Release signing key + second SHA-1 | Needs the user to choose and keep a keystore password |
| I-005 | Show which Google account is connected | Needs an extra profile/email scope; only matters with 2 accounts on the phone |
| I-006 | Light-mode drawings for all canvas screens | Design, deferred by the user |

## Known risks that need a real phone and real data

| ID | Risk |
|---|---|
| R-001 | Google may refuse the Android GIS authorization flow for the Health scopes (docs only show web clients). Fallback: AppAuth + Custom Tabs inside `GoogleHealthAuth`. |
| R-002 | With two Google accounts on the phone, GIS might pick the builder account. The user must choose the Fitbit account on Google's screen. |
| R-003 | No real API responses have been seen yet, so parsers are tested only against documented shapes. |

## Lint follow-ups (from the shared lintDebug run)

| ID | Item | Status |
|---|---|---|
| U-027 | NonObservableLocale error: TodayScreen used Locale.getDefault() inside a composable | Fixed: LocalConfiguration locale; lintDebug 0 errors |
| U-028 | ConstantLocale: cached formatters in Fmt, Heart, Trends | Fixed: formatters built per call |
| U-029 | UnusedAttribute: targetCell* on API < 31 | Fixed: moved to res/xml-v31 |
| U-030 | DataExtractionRules: no fullBackupContent for Android 9-11 | Fixed: backup_rules.xml excludes everything |
| U-031 | UseKtx in MainActivity | Fixed |

## Cross-review findings from Claude 2 on Claude 1's code

| ID | Item | Status |
|---|---|---|
| X-D-001 | Midnight rollover | Fixed with U-004 (test) |
| X-D-002 | Sign-out relied on Main.immediate ordering | Fixed with U-019 |
| X-D-003 | runCatching swallowed CancellationException | Fixed: launchSafely rethrows |
| X-D-004 | Wake day recomputed with device zone | Fixed: uses SleepSession.wakeDate (MappersTest) |
| X-D-005 | No revoke on sign-out | Fixed with U-001 |
| X-D-006 | onSignedIn on every app start | Fixed: only after interactive sign-in |

## Verification run (both sessions' code together), 22 Nov 2026 09:10
`./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` → BUILD SUCCESSFUL.
Tests 60/60 pass (HealthApi 5, Parsers 12, SyncEngine 16, DashboardViewModel 2, Format 5, Mappers 9, Reports 6, SmartWidget 5). Lint: 0 errors, 2 warnings (newer Gradle / Compose BOM available; deferred on purpose until the first device run). Release APK (R8 + shrink) 4.3 MB, unsigned.
