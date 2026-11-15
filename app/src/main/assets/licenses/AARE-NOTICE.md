# AARE integration

Source: https://github.com/prototux/AARE
Pinned revision: 5d932aec4cffda124fe0b42dd5e9ed80de4f007f
Retrieved: 2026-11-12

The AAP core (`aap/src` upstream) is included in this module. Android wireless
transport files from upstream are in the application's
`net/prototux/aare/transport/wireless` package.
License: GNU GPL version 3; see LICENSE. Protocol schemas retain upstream notices.

CarBridge modifications: Kotlin 1.9.24 / Java 8 library bytecode build configuration (Gradle runs on Java 17); checked-in
Java lite protobuf output generated with protoc 4.28.3 and bundled matching
protobuf-javalite 4.28.3 runtime (BSD 3-Clause, Google). The Android 17-only local
network permission reference was omitted for this Android 14-targeting build.
No upstream user interface or app configuration is imported.

Protobuf source/license: https://github.com/protocolbuffers/protobuf/tree/v28.3
The app-specific adapter and connection screen are implemented by CarBridge.
