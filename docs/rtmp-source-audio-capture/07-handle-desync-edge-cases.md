### Overall Verdict: **Good architecture and safe to ship, with 3 small edge cases worth knowing**

Architecturally, this is the right way to solve A/V sync when tapping ExoPlayer audio with an `AudioProcessor`:
- Because `TapAudioProcessor` sits upstream of `AudioTrack` while video rendering (`MediaCodecVideoRenderer`) is paced downstream of `AudioTrack`'s playhead, **`RtmpAudioBridge` acts as a parallel delay line to `AudioTrack`**.
- Keeping ~150–160 ms in `RtmpAudioBridge` and pulling at strict `1.0x` real-time pace means both pipes have the same travel time from the start and don't drift apart from artificial rate changes.

---

### 3 Potential Edge Cases (and How Much They Matter)

1. **`AudioTrack` Buffer Size (`80–150 ms`) vs. `TARGET_BUFFER_MS` (`160 ms`)**
   - **Where:** [RtmpSourceSwitchHelper.kt](file:///Users/dmytroantonov/git/LifeStreamerV2/app/src/main/java/com/dimadesu/lifestreamer/ui/main/RtmpSourceSwitchHelper.kt#L65-L69) sets `minPcmBufferDurationUs = 80_000` (80 ms) and `maxPcmBufferDurationUs = 150_000` (150 ms), while [RtmpAudioBridge.kt](file:///Users/dmytroantonov/git/LifeStreamerV2/app/src/main/java/com/dimadesu/lifestreamer/rtmp/audio/RtmpAudioBridge.kt#L33) sets `TARGET_BUFFER_MS = 160`.
   - **What happens:** While ExoPlayer is preparing (`awaitReady()`), `DefaultAudioSink` stops pre-filling as soon as `AudioTrack` is full (at `80–150 ms`). Because `80–150 ms` is slightly less than `160 ms`, `ExoPlayerAudioSource` starts with `isPrebuffering = true` and pads `10–20 ms` of silence after playback starts until `RtmpAudioBridge` reaches `160 ms`.
   - **Impact:** Minor (~20–30 ms extra audio delay after startup or after a stall). Making `TARGET_BUFFER_MS` slightly smaller than or equal to `AudioTrack`'s pre-fill (e.g., `AudioTrack` `150–180 ms` and `TARGET_BUFFER_MS = 140 ms`) avoids that extra post-start wait.

2. **New `ExoPlayer` Pre-filling `RtmpAudioBridge` During `awaitReady()` (Reconnects & RTMP 1 -> RTMP 2 Switches)**
   - **Where:** [RtmpSourceSwitchHelper.kt](file:///Users/dmytroantonov/git/LifeStreamerV2/app/src/main/java/com/dimadesu/lifestreamer/ui/main/RtmpSourceSwitchHelper.kt#L246-L285).
   - **What happens:** During `awaitReady()` (which waits ~2.5 seconds for `bufferForPlaybackMs`), the new `ExoPlayer` is already decoding the first ~150 ms of audio into the singleton `RtmpAudioBridge` *before* `RTMPVideoSource` is attached:
     - If you are in **bitmap fallback** (reconnecting after a drop), `ExoPlayerAudioSource` is already running and will drain that pre-filled 150 ms during the 2.5-second `awaitReady()` wait—before video even starts.
     - If you switch directly from **RTMP 1 to RTMP 2**, RTMP 1's player is still playing for those 2.5 seconds while RTMP 2's warming-up player also writes into `RtmpAudioBridge`.
   - **Impact:** Can cause a brief audio hiccup or ~150 ms offset after a reconnect or RTMP-to-RTMP switch.

3. **Overflow at `220 ms` Stays at `220 ms` Instead of Snapping Back to `160 ms`**
   - **Where:** [`writeToRingLocked()` in RtmpAudioBridge.kt](file:///Users/dmytroantonov/git/LifeStreamerV2/app/src/main/java/com/dimadesu/lifestreamer/rtmp/audio/RtmpAudioBridge.kt#L356-L362).
   - **What happens:** If a brief thread/GC hiccup during a long stream pushes the buffer from `160 ms` up to `MAX_BUFFER_MS` (`220 ms`), `writeToRingLocked()` only drops the excess above `220 ms` rather than trimming back to `TARGET_BUFFER_MS` (`160 ms`), so it rides at `220 ms` (+60 ms) from then on.
   - **Impact:** Very small (+60 ms is under 2 video frames at 30 fps and within broadcast lip-sync tolerance), though trimming to `targetFillBytes` when `maxFillBytes` is exceeded would keep long sessions centered at `160 ms`.

---

### Implementation Adjustments Made

1. **Aligned `AudioTrack` buffer capacity with `TARGET_BUFFER_MS`**
   - Lowered `TARGET_BUFFER_MS` from `160` ms to `140` ms in [RtmpAudioBridge.kt](file:///Users/dmytroantonov/git/LifeStreamerV2/app/src/main/java/com/dimadesu/lifestreamer/rtmp/audio/RtmpAudioBridge.kt#L33-L34) and updated `DefaultAudioTrackBufferSizeProvider` to `150_000`–`180_000` us in [RtmpSourceSwitchHelper.kt](file:///Users/dmytroantonov/git/LifeStreamerV2/app/src/main/java/com/dimadesu/lifestreamer/ui/main/RtmpSourceSwitchHelper.kt#L65-L70) so ExoPlayer's initial `AudioTrack` pre-fill reliably hits `>= 140` ms before `STATE_READY` without extra startup silence padding.

2. **Staged `TapAudioProcessor` warm-up until `awaitReady()` succeeds**
   - Added `startStaged` mode, bounded per-processor staging (`MAX_BUFFER_MS`), `promotePendingTap()`, and superseded/source checks in [RtmpAudioBridge.kt](file:///Users/dmytroantonov/git/LifeStreamerV2/app/src/main/java/com/dimadesu/lifestreamer/rtmp/audio/RtmpAudioBridge.kt#L143-L167), creating staged taps and promoting them in [RtmpSourceSwitchHelper.kt](file:///Users/dmytroantonov/git/LifeStreamerV2/app/src/main/java/com/dimadesu/lifestreamer/ui/main/RtmpSourceSwitchHelper.kt#L74).
   - Prevents a connecting player from mixing audio into a live RTMP stream or getting drained early during fallback, protects against late frames/resets from a superseded player, and lets failed/timed-out attempts reset cleanly without disturbing active playback.

3. **Hysteresis overflow trim, consumer stall resync, and rebuffer recovery**
   - Updated `writeToRingLocked` in [RtmpAudioBridge.kt](file:///Users/dmytroantonov/git/LifeStreamerV2/app/src/main/java/com/dimadesu/lifestreamer/rtmp/audio/RtmpAudioBridge.kt#L407-L415) so overflows past `220` ms snap back to `140` ms instead of hovering pinned at the `220` ms ceiling.
   - Added `trimToTargetBuffer()` in [ExoPlayerAudioSource.kt](file:///Users/dmytroantonov/git/LifeStreamerV2/app/src/main/java/com/dimadesu/lifestreamer/rtmp/audio/ExoPlayerAudioSource.kt#L139-L144) on consumer stalls (`scheduledNs != 0L && nowNs - scheduledNs > MAX_DRIFT_NS`) and in [PreviewViewModel.kt](file:///Users/dmytroantonov/git/LifeStreamerV2/app/src/main/java/com/dimadesu/lifestreamer/ui/main/PreviewViewModel.kt#L3052-L3060) on `STATE_READY` recovery from `STATE_BUFFERING`.
