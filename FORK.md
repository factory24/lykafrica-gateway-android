# FORK.md — Lyk Africa Gateway (Svara device provider)

- **Upstream:** https://github.com/capcom6/android-sms-gateway (Apache-2.0, see LICENSE; NOTICE kept).
- **Fork point:** upstream `master` at 4937893 (= v1.77.5), 2026-10-25. Remote `upstream` is set.
- **Why a fork:** user decision 2026-10-24 (companies/svara/SUPPORT-BRIEF-2026-10-24.md, OWNAZ-90): SMS and call
  features through the user's own phone and SIM, as Svara's `provider = device`.
- **Rule:** rebase on upstream, never merge. Our changes are small and marked `[lyk]`; new features go in new
  files/packages (`africa.lyk.*` when added), upstream files only change at the hooks below.

## Hooks into upstream files
| File | Change |
|---|---|
| `app/build.gradle` | Firebase plugin only when `google-services.json` exists; `applicationId` + `app_name` from env `GATEWAY_APP_ID` / `GATEWAY_APP_NAME` (falls back to upstream values) |
| `app/src/main/res/values/strings.xml` | `app_name` removed (comes from Gradle); `sim_for_sending`, `no_active_sim` added |
| `modules/messages/MessagesSettings.kt`, `MessagesService.kt` | `SimSelectionMode.Fixed` + `default_sim_slot` setting; per-message `simNumber` still wins |
| `res/xml/messages_preferences.xml`, `res/values/arrays.xml`, `ui/settings/MessagesSettingsFragment.kt` | "SIM for sending" picker listing active SIMs (carrier · number · slot) |
| `MainActivity.kt` | Tab 0 is `africa.lyk.gateway.ui.LykHomeFragment` (connect once with an access code, SIM list + choice, test message, Advanced); upstream `HomeFragment` stays in the tree |
| `modules/gateway/GatewaySettings.kt`, `GatewayService.kt` | `deviceName` (the phone's name on the server, set on the connect screen) used when registering, so several phones are told apart |
| `app/build.gradle` | `lyk_default_server_url` from env `GATEWAY_SERVER_URL` (prefilled server on the connect screen) |

## Build
`GATEWAY_APP_ID=africa.lyk.gateway GATEWAY_APP_NAME="Lyk Africa Gateway" ./gradlew assembleDebug` (JDK 17).

New files live under `app/src/main/java/africa/lyk/gateway/` and `res/*/lyk_*`.
