# Vendored LibADB Android

Source: [MuntashirAkon/libadb-android, tag 3.1.1](https://github.com/MuntashirAkon/libadb-android/tree/3.1.1).
The upstream `libadb/src/main/java` and manifest are included, with the patches listed below.
Downloaded source ZIP SHA-256: `8cdb9b909991ab96da211b293175357345c9d727a22221f36b8d573da7bb1de8`.

License choice for the dual-licensed library: Apache-2.0. Original per-file SPDX identifiers,
COPYING, and all upstream LICENSES files are preserved. Some incorporated files additionally
carry BSD-3-Clause or MIT terms. The SPAKE2 and Conscrypt dependencies retain their own licenses.

## CarBridge patches

- `AdbStream` / `AdbInputStream`: drain both buffered and queued data after remote CLSE,
  then return EOF; zero-length reads return zero; single-byte reads are unsigned.
  Each WRTE fragment waits for its own OKAY, including writes larger than negotiated maxData.
  Validate array bounds and preserve interrupt status on interrupted reads/writes.
- `AdbConnection`: predicate-based OPEN wait prevents losing an early OKAY and bounds service
  opening with an additional `open(destination, timeoutMillis)` overload. Stream IDs are allocated
  safely during concurrent OPENs. Connection waits use monotonic time and handle an infinite timeout
  without overflowing a deadline. TCP establishment defaults to 10 seconds, with an additional
  `Builder.setConnectTimeoutMillis()` so offline reconnect can share a bounded TCP/AUTH budget.
- `AdbConnection.close()`: retains the application-owned RSA identity, which is also referenced
  by the library's cached TLS context; exposes `isTls()` for requiring STLS in CarBridge and
  `isRsaAuthenticated()` for requiring a successful legacy AUTH_SIGNATURE before CNXN in the
  explicitly selected offline LOCAL_TCP mode. A bare CNXN does not qualify.
- Offline 0.4.1: packet writes have an independent 10-second raw-socket watchdog, including
  waiting for the packet writer lock. A shorter OPEN deadline also covers sending OPEN; timeout
  discards that transport instead of attempting an unbounded CLSE. WRTE acknowledgement waits
  are limited to 10 seconds per fragment. Graceful stream CLSE defaults to 1 second, with an
  overload for a shorter remaining budget. Socket abort never takes the packet lock or performs
  TLS close_notify. Transport close waits at most 500 milliseconds, never joins its own thread,
  and preserves the caller's interrupt flag. Dead-transport cleanup only notifies local streams.
  Stream monitors are not held during potentially blocking socket writes. Watchdogs capture the
  old connection object and cannot close a later replacement session.
- `PairingConnectionCtx`: bounded TCP/TLS/pairing reads, cancellation by closing the socket,
  safe cleanup before TLS/SPAKE initialization, and no peer-identifier byte dump in logs.
- `PairingAuthCtx`: idempotent destruction synchronized with native SPAKE/cipher operations.
- `SslUtils`: require bundled Conscrypt rather than falling back to Android's hidden
  `exportKeyingMaterial` API. No hidden-API exemption is installed.
- The Gradle module builds against API 34 with Java 8 bytecode and minSdk 30. Native libraries
  come from pinned SPAKE2 2.2.1 and Conscrypt 2.5.3 dependencies; no ABI filters are imposed.

Both resolved AARs contain `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` JNI libraries.
All PT_LOAD segments of all eight packaged ELF libraries report 16384-byte alignment;
this verifies the native segment requirement for Android devices with 16 KiB pages.

## Regression checks

`AdbStreamRegressionTest` runs the real vendored transport against a loopback fake ADB daemon.
It checks queued and buffered EOF, unsigned single-byte reads, per-fragment flow control,
bounded refused/unanswered OPEN, and concurrent OPEN IDs with immediate responses.
It also fills a real TCP send buffer with a 32 MiB write while the peer stops reading, checks a
200 ms write deadline and a 150 ms OPEN deadline behind a congested writer, confirms that abort
wakes WRTE acknowledgement waiters, preserves interruption during close, and reconnects with a
fresh transport after the failure.

`AdbIdentityTest` checks a generated RSA-2048/X509 identity through the actual PKCS8 and X509
decoders and verifies that the restored key can sign for the restored certificate.
`LocalAdbAuthenticationTest` independently frames a real legacy AUTH challenge and verifies the
RSA response against the original challenge data, then checks rejection of unauthenticated CNXN
and rejection without public-key fallback. `OfflineAdbActivationTest` covers exact restart ACKs,
ACK followed by close failure, stalled ACK timeout, and actual loopback listener refusal/open state.
Offline 0.4.1 adds independent ACK reader futures and bounded asynchronous cleanup (at most one
second per cleanup wait), plus a regression where both reader and close ignore interruption:
one stuck close must not prevent the next acknowledgement deadline from firing.

The app additionally limits waiting for its operation lock to 1.5 seconds. The public connect
deadline starts before that wait and covers old-transport teardown, waiting for the one persistent
identity task, TCP establishment, and AUTH/STLS. Final failed-connection cleanup may add at most
the 500 ms join allowance. Probes share one 2-second budget across OPEN and graceful CLSE.
These transport limits complement the UI operation deadline; they do not prove a particular
phone's adbd remains running when Wi-Fi is disabled.

Run `:libadb:testDebugUnitTest :app:testDebugUnitTest` from the project root.
Tests do not claim physical-device Wireless debugging or scrcpy validation.
