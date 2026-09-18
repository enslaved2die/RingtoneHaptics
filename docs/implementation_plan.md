# Implementation Plan: Haptic Instruments (Stem/Stinger-Driven Haptics)

**Date:** 2026-09-15
**Status:** Draft — awaiting approval

---

## 1. Problem Recap

The current pipeline (`AdvancedHapticAnalyzer` → `AdvancedHapticSynthesizer`) generates haptics from heuristics run directly on the mixed audio signal (band-pass filters + amplitude thresholds + beat-snapping). Two failure modes:

1. **Fades/crescendos/drops are missed** — peak-based heuristics only fire on sudden spikes, not smooth volume rises/falls.
2. **No instrument identity** — a bass note, a kick, and a vocal syllable all look like "energy in a frequency band" to the current filters; they can't be told apart when mixed.

## 2. Decision: Two-Phase Approach, Not One

Research into both candidate solutions came back with a clear split:

| | **HPSS + Transient + Energy-Slope (DSP-only)** | **ML Stem Separation (Demucs htdemucs, ONNX)** |
|---|---|---|
| Fixes fades? | Yes — purpose-built for this | Indirectly (better isolation helps, but not the core mechanism) |
| Fixes instrument identity? | Partially — 2-way harmonic/percussive split | Yes — real drums/bass/vocals/other stems |
| Processing time (4-min song) | ~1–3 seconds | ~2–6 minutes (CPU-only, no reliable NNAPI/GPU path for Demucs's transformer blocks yet) |
| App/model size | ~0 (no model weights, small FFT lib) | +80–170MB |
| Implementation complexity | Moderate (FFT + median filter + peak-picking, all deterministic C++) | High (model export/quantization, delegate integration, per-chipset testing) |
| Battery impact | Low, one-shot burst | Higher, sustained NN inference |
| Reuses existing code | Yes — extends `BpmBeatDetector`'s novelty-curve machinery and `AdvancedHapticSynthesizer`'s additive mixing directly | No — net-new subsystem |

**Recommendation: build Phase 1 (HPSS/DSP) now as the default pipeline upgrade. Treat Phase 2 (ML stem separation) as an explicit opt-in "Enhanced Mode" later**, not a replacement — it's the more accurate but far heavier option, better suited as a premium/optional path once Phase 1's limits (can't distinguish bass vs. kick — both are low + percussive) are actually felt.

This plan covers **Phase 1 in full detail** and scopes Phase 2 at a high level for later approval.

---

## 3. Phase 1: HPSS + Haptic Instruments (DSP-only)

### 3.1 Harmonic-Percussive Source Separation (HPSS)

Classic Fitzgerald/librosa approach, implemented in C++ (new file, `app/src/main/cpp/hpss.cpp`):

1. STFT the mixed PCM (2048-sample window, 512 hop — reuse existing FFT-capable code path or vendor a small FFT lib, e.g. `pffft` or `kissfft`, MIT-licensed, trivial to add to `CMakeLists.txt`).
2. **Harmonic estimate `H`**: median filter along the *time* axis per frequency bin (window ~17–31 frames) — harmonic content is stable in frequency, continuous in time.
3. **Percussive estimate `P`**: median filter along the *frequency* axis per frame (window ~17–31 bins) — percussive content is broadband, brief.
4. Soft (Wiener) masks `M_h = H^p/(H^p+P^p)`, `M_p = 1-M_h`; apply to complex STFT; inverse-STFT both.
5. Runs once per song, offline, in a background coroutine — not realtime. Expected cost: ~1–3s for a 4-minute track on ARM64 (NEON-friendly sliding-window median, not full-sort-per-window).

### 3.2 Stinger / Transient Detection (on the percussive component `P`)

Extends the exact pattern `BpmBeatDetector.kt` already uses for its novelty curve, but per-bin instead of low-passed broadband:

- **Spectral flux**: `SF[n] = Σ_k max(0, |P[n,k]| - |P[n-1,k]|)`.
- Optionally add **complex-domain onset detection** (predict bin phase/magnitude from steady-state assumption, measure deviation) — catches soft attacks flux misses; nearly free since the complex STFT already exists from HPSS.
- Adaptive peak-picking (median + k·MAD local threshold) with ~50ms debounce → onset timestamps.
- Each onset defines a **stinger window** (onset → next onset, capped at ~150–300ms), classified by spectral centroid / low-vs-high energy ratio within the window → routes to `THUMP` (low, <120Hz-dominant) vs `SNAP_CLICK` (mid-high, 200Hz–4kHz-dominant).

### 3.3 Fade-Up/Fade-Down Detection (on the harmonic component `H`, or full mix)

New file `app/src/main/java/.../domain/dsp/FadeEnvelopeDetector.kt` (Kotlin is fine here — this is a lightweight post-process on already-decimated envelope data, not per-sample DSP):

```
env[n]   = one-pole smoothed RMS envelope, ~10-20ms hop
slopeDb[n] = (20*log10(env[n]) - 20*log10(env[n-W])) / (W/1000)   // dB/sec, over W ∈ {500ms..3000ms}

if slopeDb[n] >  RISE_THRESH_DB_S   (e.g. +3 dB/s):  candidate CRESCENDO start, enter debounce
if slopeDb[n] < -RISE_THRESH_DB_S:                    candidate DECRESCENDO start, enter debounce

post-process: merge adjacent same-direction candidates within 200ms gap,
              require >= 400ms duration and >= 6dB total delta to keep (reject flutter)
```

Multiple window sizes can run in parallel (500ms catches short riser stabs, 3000ms catches long build-ups); prefer the longer-window match when both fire, to avoid double-triggering.

Detected fade spans feed directly into `SWELL` clip generation **as the actual ramp envelope**, replacing the current fixed quadratic ramp / fixed `endLevel - startLevel > 0.45f` threshold in `generateCinematicImpact` (`AdvancedHapticAnalyzer.kt`) with a real detected curve.

### 3.4 "Haptic Instruments" Data Model

Current state: `HapticClip` is a flat data class with no lane/instrument concept; `EditorUiState` holds one flat `List<HapticClip>`; `AdvancedHapticSynthesizer.synthesizeChannel2` additively sums all clips into one buffer; the NDK encoder hardcodes exactly 3 channels (`ogg_vorbis_encoder.cpp:70`) — one haptic channel, non-negotiable without changing the OGG/Android haptic-ringtone spec itself (out of scope).

Changes:

1. **`HapticInstrument` enum** (new, `domain/model/HapticInstrument.kt`): `PERCUSSION`, `GROOVE_BASS`, `LEAD_CADENCE`, `DYNAMIC_ENVELOPE` — matching the 4 conceptual instruments in the handoff's architecture diagram. Each maps to a default set of allowed `HapticPatternType`s (e.g. `PERCUSSION` → `THUMP`/`SNAP_CLICK`/`DOUBLE_TAP`; `DYNAMIC_ENVELOPE` → `SWELL`).
2. **`HapticClip` gains an `instrument: HapticInstrument` field** (default `PERCUSSION` for backward compatibility with existing manually-stamped clips).
3. **`EditorUiState`** gains per-instrument visibility state: `mutedInstruments: Set<HapticInstrument>`, `soloedInstrument: HapticInstrument?` — clip list stays flat (`List<HapticClip>`), filtered by instrument for display/export. This avoids restructuring the whole state shape into nested maps.
4. **`AdvancedHapticAnalyzer`** gains a new entry point, e.g. `generateInstrumentClips(pcm, sampleRate, durationMs, beatGrid, hpssResult, fadeSpans, config): List<HapticClip>`, which runs the HPSS-based pipeline and tags each emitted clip with its source `HapticInstrument`. The existing preset-based `generateClipsForPreset` stays as-is (still useful as a fast/manual mode or fallback when HPSS is skipped) — this is additive, not a replacement of existing presets.
5. **`AdvancedHapticSynthesizer.synthesizeChannel2`** needs **no structural change** — it already additively sums a flat `List<HapticClip>` into one buffer; muted instruments are simply filtered out of the list before calling it. This is the cheapest part of the whole plan.

### 3.5 DAW UI Changes

`MultiLaneDawTimeline.kt` currently hardcodes exactly 2 visual lanes (waveform + one flat clip lane) in a single Canvas. Rework:

- Replace the single hardcoded haptic-clip lane with **N sub-lanes, one per `HapticInstrument` with clips present** (color-coded, consistent with the existing pattern-type color coding).
- Add a compact **mute/solo strip** (left rail, one row per instrument) wired to the new `mutedInstruments`/`soloedInstrument` state.
- Fade spans render as a gradient/ramp overlay on the waveform lane (visual only, no interaction) — matches the handoff's "ramp gradients on clip bars" request for `SWELL`/`DYNAMIC_ENVELOPE`.
- `ClipPropertySheet.kt`, `PatternPaletteDock.kt`, `PresetSelectorBar.kt` need no structural change — a clip's `instrument` field can be shown as a read-only badge in the property sheet; manual pattern stamping still works exactly as today (manually stamped clips default to `PERCUSSION` or whatever instrument matches the currently-focused lane).

### 3.6 Orchestration (`EditorViewModel.kt`)

`loadAudio()` gains, after the existing `AudioDecoder.decodeAudio` → `BpmBeatDetector.detectBeatGrid` steps:

```
hpssResult = HpssProcessor.separate(pcm, sampleRate)          // new, native call
stingers   = TransientDetector.detectStingers(hpssResult.percussive)   // new
fadeSpans  = FadeEnvelopeDetector.detect(hpssResult.harmonic)  // new, Kotlin
instrumentClips = AdvancedHapticAnalyzer.generateInstrumentClips(...)  // new entry point
```

This runs in the same background coroutine already used for analysis; add a progress indicator (HPSS + detection is ~1-3s, not enough to need a cancel button, but worth a spinner state since it's a new perceptible delay vs. today's instant preset generation).

---

## 4. Phase 2 (Future, Separate Approval): ML Stem Separation "Enhanced Mode"

Scoped but **not started** without a separate go-ahead, given the cost profile:

- **Model**: htdemucs (4-stem: drums/bass/vocals/other) exported to ONNX; run via **ONNX Runtime Mobile** with XNNPACK (CPU) as the reliable baseline, NNAPI/GPU delegate as an optional speed-up only (transformer-block delegate support is unverified — budget for CPU fallback).
- **UX**: opt-in only. Wi-Fi-gated one-time model download (~80–170MB), explicit "this may take a few minutes" messaging, foreground service (not a plain coroutine) to survive Doze/process death during the 2–6 minute processing window.
- **Integration point**: would slot in as an alternative source for the same `HapticClip(instrument=...)` model Phase 1 introduces — i.e., Phase 1's data model changes are a prerequisite for Phase 2, not throwaway work.
- **Risk**: separation quality varies by genre (drums/bass usually clean; vocal/other bleed is common), app size growth, battery cost, per-chipset delegate testing burden.

---

## 5. Milestones (Phase 1)

1. Vendor FFT lib (pffft/kissfft) + implement `hpss.cpp` (native), unit-test against a few known audio clips (verify H/P separation sounds right when exported to WAV for manual listening).
2. Implement `TransientDetector` (spectral flux + peak-picking) on top of `P`.
3. Implement `FadeEnvelopeDetector` (pure Kotlin, testable in isolation) on top of `H`.
4. Add `HapticInstrument` enum + `HapticClip.instrument` field + `EditorUiState` mute/solo state.
5. Add `AdvancedHapticAnalyzer.generateInstrumentClips` wiring HPSS/transient/fade outputs into tagged `HapticClip`s.
6. Wire into `EditorViewModel.loadAudio()` with progress state.
7. Rework `MultiLaneDawTimeline` for per-instrument sub-lanes + mute/solo strip + fade gradient overlay.
8. Test suite: extend existing `./gradlew test` coverage for `HpssProcessor`/`TransientDetector`/`FadeEnvelopeDetector` with synthetic signals (known sine sweep for fades, known click train for transients).
9. Manual verification on the connected Pixel 11 Pro (`66091FDKX001ZS`) — install, generate a ringtone from a real song with a clear drop/buildup, confirm the `SWELL` timing now tracks the actual crescendo and drums feel distinct from bass.

---

## 6. Open Questions for You

1. Approve Phase 1 (HPSS + DSP) scope as described? This is the part I'd start on.
2. Phase 2 (ML stems) — confirm it should stay deferred/opt-in rather than attempted now, given the 80-170MB / multi-minute cost.
3. Any preference on FFT library (pffft vs kissfft vs something else already vetted for this project)?
4. OK with a new perceptible processing delay (~1-3s) on audio load for the HPSS step, with a spinner?
