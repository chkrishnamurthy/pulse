# Direct data: three lanes (23 Nov 2026, Claude 1, asked for by the user)

| Lane | Route | What | Saved? | Code |
|---|---|---|---|---|
| 1 | Band → Pulse (Bluetooth Heart Rate Profile) | Live heart rate, while the screen is open | No | direct/LiveHeart, ui/screens/LiveHeartScreen |
| 2 | Band → Google Health app → Health Connect → Pulse | Today's steps, distance, floors, heart rate; checked every 20 s while Pulse is open | No (memory only) | direct/LocalLane, ui/screens/FasterDataCard |
| 3 | Band → Google Health app → Google's servers → Pulse | Everything | Yes, the main record | sync/SyncEngine (unchanged) |

Joining rules (direct/Lanes, unit tested): for today only, the bigger total wins; Health Connect heart rate is added only after the
newest cloud sample; only Fitbit sources count (app ID `com.fitbit.*`, or the record says it is from a Fitbit). Screens read
`MergedHealthRepository`; widgets read the saved data only. Both new lanes are off until the user turns them on.

Shared files touched for this: `gradle/libs.versions.toml`, `app/build.gradle.kts` (Health Connect 1.1.0). No data/sync code changed.

## Bugs found on the emulator and fixed

| ID | Bug | Fix |
|---|---|---|
| E-1 | The Fitbit check matched "fitbit" anywhere, so Pulse's own app ID counted as the band | Exact prefix `com.fitbit.`; test added |
| E-2 | Health Connect totals can be empty for an app that is not on its priority list | Add up the band's own records when the total is empty |
| E-3 | (older) After sign-out, Pulse showed Today with sample data instead of the sign-in screen | Start screen is decided once in PulseApp |

## Checked on the emulator (Android API 37)

Start, Today, Heart, Settings, live-heart screen, Bluetooth permission and search, Health Connect permission, "no Fitbit data",
other brand ignored, fake Fitbit data shown (steps and heart rate, +500 steps within 20 s), sign-out (lands on Connect; Health
Connect permission gone after the app closes). The temporary data writer was removed afterwards.

## Not checked (needs the real phone and band)

- Whether the Air lets an app on the same phone receive its heart rate.
- Whether and how fast Google Health writes to Health Connect on this phone.
- Whether Google Health ever writes overlapping step records (only matters for the E-2 fallback).
- Android 9 to 13.
