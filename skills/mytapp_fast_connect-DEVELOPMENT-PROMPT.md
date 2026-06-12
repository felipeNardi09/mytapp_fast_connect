# `mytapp_fast_connect` — Development Prompt

> Hand this file back to Claude (or paste its contents) when you're ready to build the
> library. It is self-contained. Nothing here should modify the existing app repo.

---

## Build the `mytapp_fast_connect` BLE communication library + wrappers + docs.

**Context:** Extract and generalize the BLE messaging logic currently embedded in the
Android app at `/home/felipe/Documentos/Trabalho/pague_e_retire_pos` into a standalone,
reusable library. Source of truth for the protocol and BLE mechanics:

- `app/src/main/java/com/mytapp/mytapppagueeretire/bluetooth/BleManager.kt` — GATT, MTU 512,
  chunking = MTU−3, write queue with ≤10 retries / 300ms backoff, notification/indication
  subscription (CCCD), API-33+ and legacy callback paths.
- `app/src/main/java/com/mytapp/mytapppagueeretire/bluetooth/BleConnectionState.kt` — states:
  `Disconnected` / `Connecting(address)` / `Connected(name, address)` / `Error(message)`.
- `app/src/main/java/com/mytapp/mytapppagueeretire/bluetooth/FastConfigMessage.kt` — CONFIG
  builder + LED normalization + `adjust × 1000` rounding.
- `PaymentViewModel.kt` lines 884–885 — INIT_SERVING.
- `ManagerCardViewModel.kt` — `MANAGER_START&` / `MANAGER_STOP&`.

**Protocol (UTF-8, `&`-terminated):**

- `CONFIG:{volumeShown},{stepsForward},{stepsBackward},{tick},{round(adjustSeconds*1000)},{cupDist},{normalizedLedColor RRGGBB},{clientTimeout},{hall 1|0}&`
- `INIT_SERVING:{liquid},{foam},{foamDecayMinutes},{foamMsAfterDecay}&`
- `MANAGER_START&`, `MANAGER_STOP&`
- Incoming examples to handle as raw strings: `STATUS:IDLE`, `SERVING:{volume},...`,
  `FINISH_SERVING:{volume},...`

---

## Architecture — two layers + wrappers (do NOT modify the existing app)

Create a new, separate Gradle project at `../mytapp_fast_connect` (sibling of the app, **not**
inside it). Toolchain must match the app: **Kotlin 2.1.20, AGP 8.5.0, Gradle 8.7, minSdk 24,
compileSdk/targetSdk 34**. Group `com.mytapp.fastconnect`, start at version `0.1.0`.

```
mytapp_fast_connect/
├── settings.gradle.kts
├── build.gradle.kts                 ← root, version + publishing config
├── gradle/libs.versions.toml        ← Kotlin 2.1.20, AGP 8.5.0
├── mytapp_fast_connect-core/        ← LAYER 1 — pure Kotlin/JVM (JAR), no Android
├── mytapp_fast_connect-android/     ← LAYER 2 — com.android.library (AAR)
├── flutter_mytapp_fast_connect/     ← Flutter plugin (MethodChannel + EventChannel → AAR)
├── react-native-mytapp-fast-connect/← RN native module (@ReactMethod + emitter → AAR)
└── sample-app/                      ← minimal Android app proving the API end-to-end
```

### 1. `mytapp_fast_connect-core` — pure Kotlin/JVM module, **no Android imports**

Turn on `explicitApi()`. Contains:

- **`Transport` interface** — the seam between layers:
  ```kotlin
  interface Transport {
      val isConnected: Boolean
      val incomingMessages: Flow<String>
      suspend fun send(message: String): Boolean
  }
  ```
- **Neutral param data classes** (must NOT reference the app's `TapInfoResponse`):
  ```kotlin
  data class ConfigParams(
      val volumeShown: Int, val stepsForward: Int, val stepsBackward: Int,
      val tick: Int, val adjustSeconds: Double, val cupDist: Int,
      val ledColor: String, val clientTimeout: Int, val useHallPositioning: Boolean,
  )
  data class ServingParams(
      val liquid: Int, val foam: Int, val foamDecayMinutes: Int, val foamMsAfterDecay: Int,
  )
  ```
- **Pure message builders** — port the CONFIG logic (incl. `normalizeLedColor` and
  `round(adjustSeconds * 1000)`), INIT_SERVING, and MANAGER. Builders return either a valid
  string or a typed validation failure — **no nulls, no `android.util.Log`** (use a small
  `Logger` interface defined in core).
- **`MyTappFastConnectClient(transport: Transport)`** facade:
  ```kotlin
  suspend fun sendConfig(params: ConfigParams): SendResult
  suspend fun sendInitServing(params: ServingParams): SendResult
  suspend fun managerStart(): SendResult
  suspend fun managerStop(): SendResult
  val incomingMessages: Flow<String>
  ```
  Each method validates `transport.isConnected` **first** (returns `NotConnected` if false),
  then builds the protocol string from the object, then sends.
- **`SendResult` sealed type** — the single result shape that crosses all four languages:
  ```kotlin
  sealed class SendResult {
      object Success : SendResult()
      object NotConnected : SendResult()
      data class InvalidPayload(val field: String) : SendResult()
      data class TransportError(val reason: String) : SendResult()
  }
  ```
- **JVM unit tests** using a fake `Transport`: assert exact byte strings for each message type,
  connection-validation behavior, LED-color normalization + adjust edge cases, and
  `InvalidPayload` on missing/invalid fields.

### 2. `mytapp_fast_connect-android` — `com.android.library` (AAR), depends on core

- **`BleConnectionManager`** — port `BleManager.kt`'s scan / connect / disconnect / state / MTU /
  chunking / write-queue / notification logic; keep both the API-33+ and legacy paths.
- **`BleTransport : Transport`** — adapts the manager: `isConnected` from connection state;
  `send` via the write queue; `incomingMessages` from the notification callback exposed as a
  `SharedFlow<String>`.
- Re-expose a connection-state type and an entry point so a Kotlin/Java app does:
  create manager → connect → wrap in `BleTransport` → `MyTappFastConnectClient`.
- Declare the BLE `<uses-permission>` entries in the library manifest; **document that runtime
  permission requests stay with the host app** (the library has no Activity/UI).
- Provide `consumer-rules.pro` if any reflection/keep rules are needed.

### 3. `flutter_mytapp_fast_connect` — Flutter plugin wrapping the AAR

- `MethodChannel` for `connect` / `disconnect` / `sendConfig` / `sendInitServing` /
  `managerStart` / `managerStop` (params passed as maps).
- `EventChannel` for incoming messages and connection state.
- Dart API mirrors `MyTappFastConnectClient` with typed Dart classes and a `SendResult`
  equivalent. Include a Dart `example/` app.

### 4. `react-native-mytapp-fast-connect` — RN native module wrapping the AAR

- `@ReactMethod`s returning Promises that resolve to a `SendResult` map.
- A `DeviceEventEmitter` / `NativeEventEmitter` for incoming messages and connection state.
- Ship TypeScript typings mirroring the API. Include a JS usage example.

### 5. `sample-app` — minimal Android app

Requests BLE permissions, connects, sends each message type, and prints incoming messages —
proving the public API end-to-end.

---

## Documentation (`README.md` + `docs/`)

- Quick-start per platform: Kotlin, Java, Flutter, React Native.
- The full protocol spec (with a protocol version number — firmware and lib evolve together).
- The permissions / runtime-request responsibility note.
- The `SendResult` error table.
- How to build the AAR locally and consume via `mavenLocal()`.
- A publishing section: **JitPack (primary)**, Maven Central as a later option.
- A `CHANGELOG.md` and a semantic-versioning policy.

---

## Constraints

- Do **not** touch the existing app repo.
- Keep the public API minimal and `explicitApi`-clean.
- The library must **not** depend on app models (`TapInfoResponse`, `TapConsumptionHolder`,
  `BarInfoHolder`, etc.) — neutral data classes only.
- Everything testable without a device goes in `mytapp_fast_connect-core` JVM tests.
- After scaffolding, produce a follow-up checklist of what still needs a **physical device** to
  verify (connection, MTU negotiation, chunked writes, notifications).

---

## Follow-up (separate PR, not part of this build)

Once the library exists and is verified, migrate the app's `PaymentViewModel` and
`ManagerCardViewModel` call sites to map their holders → `ConfigParams` / `ServingParams` and
call `MyTappFastConnectClient`. Do this as its own change — do not combine it with building the
library.
