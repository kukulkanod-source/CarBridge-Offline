# HSD references

Source: https://github.com/SadykovIV/HSD/tree/v0.8.1
License: GNU General Public License v3.0; full text in LICENSE.

CarBridge 0.87 adapts the contact-lifetime approach from
`android/app/src/main/java/com/hsd/universal/aa/ProjectionTouchStream.kt`
in `app/src/main/java/com/example/carbridge/link/CarLifeTouchStream.kt`.

The missing-AVC-parameter-set recovery in `H264.withParameterSets` was informed by
`android/app/src/main/java/com/hsd/universal/Protocol.kt` (`withAvcParameterSets`).
It retains CarBridge's own framing, parser, video queue and encoder.

The AA KeyChain integration in 0.86 used the HSD v0.7.3 ProjectionIdentity and
Cryptor implementations as a GPL-3.0-compatible reference. AA protocol behaviour
has not been updated from HSD v0.8.1 in 0.87.

No HSD APK or runtime dependency is required by CarBridge.
