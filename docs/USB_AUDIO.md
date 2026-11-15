# USB audio experiment 0.35

## Reference facts

Provided APK: C:/Users/dor19/Downloads/headswap-2.5.0.apk.
Its classes.dex SHA256 matches the locally analysed dex: 285248f2895e9f905be80be9d740c25ba903dc6aa76b7ef3c4749979ddf7e082.
Only wire-format observations were used; no dependency on installing Headswap was added.

- MEDIA channel 3, INIT 0x30001, STOP 0x30002, PAUSE 0x30003, RESUME 0x30004, INIT_DONE 0x30005, DATA 0x30006.
- INIT protobuf fields 1=sample rate, 2=channels, 3=bits. Initial profile 48000, 2, 16 matches observed reference media initialisation and the scrcpy raw format.
- Data header is the existing 12-byte CarLife data header, payload PCM s16le. Reference audio-data timestamps use low 32 bits of currentTimeMillis; INIT/STOP use 0. Chunks 3840 bytes, 20 ms, below reference MAX_CHUNK 10240.
- MUSIC module is 3. MODULE_STATUS list holds count field 1 and nested entries field 2, with module id field 1/state field 2.
- MODULE_CONTROL state 1 is treated as status inquiry, not an automatic Play command. Explicit media pause/stop and another-source selection suspend capture; use the switch to re-enable if needed.

## Architecture

A separate audio-only scrcpy process is used so capture failure does not restart the display/control server. Options: video=false audio=true control=false audio_codec=raw audio_source=output; stream metadata enabled, frame metadata disabled. Open first socket, consume dummy byte then 4-byte RAW codec id 0x00726177. Codec id 0/1 is capture/config failure. Read continuous PCM in 3840-byte chunks. Android 12+ only for this first implementation.

Readiness and stop are serialized with service audio lifecycle to prevent an obsolete source emitting INIT after shutdown. Closing all its ADB channels stops the capture and releases remote-submix routing. Positive playback state/isMusicActive starts capture; recent non-silent PCM bridges transient state updates. AudioManager mode != NORMAL stops capture. No microphone, call capture or Android audio-focus request is used.

CarLife owns one packet writer. Reliable control/INIT/STOP packets are queued separately from bounded droppable PCM. Queue age threshold is 200 ms; byte budget 40000 including packet overhead. In-flight packets remain counted and are never cut in half. USB reset/recovery cancels the audio source; a new handshake permits restart while the video display is retained.

## Known limitations / physical check

No hardware verification was performed. On-HU audible output cannot be inferred from successful enqueue/write. The capture is the common output, not independently separated app audio or navigation TTS. Extra USB traffic is about 1.54 Mbps for PCM alone. Call handling, radio changes, route restoration and heat require a vehicle session. System/app audio policies may prevent capture. The checkbox is off by default and a live off-switch is retained.

Suggested first session: leave existing video settings; connect USB CarLife, start one music source, enable experiment. Compare playback and latency, pause/resume, disable to confirm ordinary routing returns, export a report. Avoid altering Bluetooth pairing as part of this first comparison.

Primary source references:
https://github.com/Genymobile/scrcpy/blob/master/doc/audio.md
https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/audio/AudioConfig.java
https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/device/Streamer.java
