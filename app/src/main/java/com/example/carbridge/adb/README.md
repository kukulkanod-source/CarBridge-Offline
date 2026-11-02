# Embedded TLS and offline local ADB client

The public Kotlin entry point is `com.example.carbridge.adb.AdbSession`.

```
pair(context, host, pairingPort, sixDigitCode): Boolean
connect(context, host, connectionPort, mode = TLS, timeoutMs = 10000): Boolean
connectLocal(context, port, timeoutMs = 10000): Boolean
isConnected(): Boolean
isConnectedFor(host, port, mode = TLS): Boolean
connectionMode: AdbConnectionMode?  // TLS or LOCAL_TCP while connected
isLocalTcp: Boolean
probeConnected(host, port, mode = TLS, timeoutMs = 2000): Boolean
activateOffline(context, port = 0): Int
deactivateOffline(context, port): Boolean
open(destination): AdbChannel
open(destination, timeoutMs): AdbChannel
disconnect()
```

Pair, connect, open, probe, activation and deactivation are blocking worker operations.
`isConnected()`, `isConnectedFor()` and mode getters read volatile state without waiting for
pairing/connect; they do not prove liveness after a daemon restart. `probeConnected()` opens and
closes a real `sync:` service under the operation lock. If the round trip fails, only the same
transport snapshot is invalidated. Pair, connect, probe and mode changes are serialized. Disconnect cancels
pending work and closes channels; the identity remains unchanged. Errors are exceptions, with a
short user-facing `lastError`; connect can also return false when its shared TCP/AUTH timeout expires.

Each `AdbChannel` exposes `input`, `output`, and `close()`. Multiple video/control/shell/sync
channels share one authenticated ADB connection. Closing a stream closes that channel, not the whole session.
The scrcpy code implements SYNC push using `open("sync:")`.

The RSA-2048 private key and self-signed X509 certificate are saved together using AtomicFile in
`Context.noBackupFilesDir/adb/identity.bin`, before pairing starts. Reconnecting, restarting the
app, or failing a pairing attempt does not regenerate the identity. Clearing app data or uninstalling
requires pairing again. Keys, certificates, pairing codes, and shell command contents are not logged.

Android 11+ Wireless debugging must be enabled in Developer options. Use the port in its pairing-code
dialog for `pair`, and the separate main Wireless debugging connection port for `connect`; these ports
can change after toggling Wireless debugging or rebooting. For the same phone, `127.0.0.1` avoids
depending on its current Wi-Fi IP. Default connections require TLS/STLS. The client never silently
falls back from TLS to classic TCP ADB.

## Explicit offline preparation

`AdbConnectionMode.LOCAL_TCP` always uses `127.0.0.1`, regardless of the supplied host. It requires
a real legacy AUTH challenge accepted with the same persisted RSA identity used for TLS pairing;
a bare CNXN or a TLS connection cannot satisfy this mode. An unrecognized key fails authorization
without automatically uploading a replacement key or initiating a new authorization prompt.

At home, enable both Wireless debugging and **USB debugging**, pair, then connect through TLS to
this same phone. `activateOffline()` requires that active local TLS session. It sends the documented
`tcpip:<port>` service request, reads the exact mode/port acknowledgment and reconnects locally with
RSA AUTH within 15 seconds. It returns the selected port only after that authenticated reconnect.
Pass 0 for a free ephemeral port, or choose 1024–65535. A failed operation may still have changed
adbd's mode; the exception explicitly describes the state as unconfirmed.

Save LOCAL_TCP and the returned port only after activation succeeds. For later starts without
Wi-Fi or a hotspot, call `connectLocal()` with the saved port. USB debugging must remain enabled.
Prepare again after reboot; OEM behavior may also require preparation after debugging is toggled.
The session API does not persist connection preferences. Classic `tcpip:` is unencrypted and adbd
binds this listener to network interfaces, even though this application connects only over loopback.

`deactivateOffline()` uses an authorized local session (TLS or LOCAL_TCP), or tries to authenticate
on the supplied local port. It requires the exact `usb:` acknowledgment, disconnects and observes
stable connection refusal on the old loopback port before returning true. A valid acknowledgment
survives a subsequent close IOException because adbd intentionally restarts at that point. Missing
ACK, timeout, or an open/unverifiable port raises an exception; callers must retain the saved mode
and port when deactivation is unconfirmed. This is a bounded observation of listener closure, not
a privileged read of adbd's system properties.

This client uses the vendored upstream LibADB Android 3.1.1 implementation, SPAKE2 2.2.1,
Conscrypt 2.5.3, and Bouncy Castle 1.81. No root, Shizuku process, Android hidden-API exemption,
or change to system permissions is performed. See `third_party/libadb/PROVENANCE.md` for patches.

`LocalAdbAuthenticationTest` uses independently framed loopback ADB packets and verifies the real
RSA signature against the original data behind the SHA-1 challenge, detecting accidental double
hashing. It also checks unsigned CNXN rejection and rejection without replacing the key.
`OfflineAdbActivationTest` checks mode ACKs, lost-close races, ACK timeouts, and open/closed TCP
listeners. Physical-device/OEM restart behavior still needs device verification.
