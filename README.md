# TriSandhya

Android app for the three daily junctions — sunrise, solar noon, and sunset — at your location.

Enable the junctions you observe, choose how long beforehand you want to be reminded, and the
app keeps itself scheduled. It re-arms every day from times it computes locally, so it tracks
the seasonal drift without you reopening it.

## How it works

**Solar times are computed on-device** using the NOAA solar equations
(`solar/SolarCalculator.kt`) — no network, no API key. Each event is solved iteratively rather
than evaluated once at local noon, which keeps it within seconds of the US Naval Observatory
across latitudes and seasons (see `SolarCalculatorTest`). Computing offline matters because the
daily re-arm runs in the background, often before dawn with no connectivity.

**Alarms are scheduled against absolute instants** via `AlarmManager`
(`alarm/SandhyaScheduler.kt`). Each firing re-arms the next day from `AlarmReceiver`;
`BootReceiver` restores the schedule after a reboot, app update, or timezone change; and a
daily `DailyRescheduleWorker` heals the chain if a link is ever dropped.

Polar latitudes are handled: on days with no sunrise or sunset the scheduler walks forward
until the junction next occurs.

## Modules

| Path | Role |
| --- | --- |
| `solar/SolarCalculator.kt` | NOAA sunrise / solar noon / sunset, computed locally |
| `alarm/SandhyaScheduler.kt` | Computes next occurrences and arms `AlarmManager` |
| `alarm/AlarmReceiver.kt` | Fires the alarm, then re-arms for the next day |
| `alarm/BootReceiver.kt` | Restores the schedule after boot / update / timezone change |
| `alarm/AlarmNotifier.kt` | Alarm channel and full-screen notification |
| `work/DailyRescheduleWorker.kt` | Daily safety net if the re-arm chain breaks |
| `data/SandhyaPrefs.kt` | Persisted toggles, offsets, and cached location |
| `MainViewModel.kt` | Screen state; survives configuration changes |
| `ui/AlarmActivity.kt` | Full-screen surface shown when an alarm fires |

## Build

Requires JDK 17+. The Gradle wrapper pins Gradle 8.4.

```bash
./gradlew :app:assembleDebug      # build the APK
./gradlew :app:testDebugUnitTest  # run the solar calculator tests
./gradlew :app:lintDebug          # Android lint
```

## Permissions

| Permission | Why |
| --- | --- |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Compute solar times for your position. Coordinates stay on the device. |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Fire at the junction rather than whenever the system batches work. |
| `RECEIVE_BOOT_COMPLETED` | Exact alarms do not survive a reboot. |
| `POST_NOTIFICATIONS` | Show the alarm. |
| `USE_FULL_SCREEN_INTENT` | Wake the screen when the device is locked. |

If exact alarms or notifications are turned off, the app shows an in-app banner linking
straight to the relevant system setting, rather than failing silently.
