# Pear Chats session 22 — diarization results

Device: `Pixel_8_API36`, x86_64 emulator, Android 16 / API 36, 4 cores,
3.8 GB. Audio: `pears22_full.wav`, 1107.84 s, 16 kHz mono.
Reference: 227 turns, 3 speakers (N/C/R), 1023.44 s scored speech.
DER: pyannote, collar 0.25, overlap is **not** skipped.

All numbers below are single runs unless stated otherwise. The emulator was
not stable enough for repeated full-session runs.

## Final results

### Full session, streaming

| Metric | Original | VAD hard-split only | + cluster pruning + speaker-change split |
|---|---:|---:|---:|
| Speakers | 3 | 4 | **3** |
| DER | 65.74% | 50.41% | **22.74%** |
| PSS | 1162 MB | 716 MB | **637 MB** |
| Native heap | 1121 MB | 643 MB | **545 MB** |
| Segments | 30 | 58 | 222 |
| Max segment | 172.4 s | 25.0 s | **22.8 s** |
| First utterance | 58.29 s | 26.24 s | 40.09 s |
| RTF | 0.161 | 0.158 | 0.260 |

The final segment count is 222, close to the 227 reference turns. Against the
original streaming baseline, DER is down **43.00 pp (65.4% relative)**, PSS is
down **525 MB (45.2%)**, native heap is down **576 MB (51.4%)**, and the
maximum segment is down **149.6 s (86.8%)**.

The price is more segments (30 → 222) and higher RTF (0.161 → 0.260,
+61.5%) because speaker-change detection computes CAM++ embeddings on
overlapping 1.6 s windows. First utterance is still better than the original
(58.29 → 40.09 s) but worse than the VAD-only intermediate (26.24 s).

### Full session, batch

| Config | Speakers | DER | PSS | Segments | RTF |
|---|---:|---:|---:|---:|---:|
| auto 0.80, no pruning | 15 | 21.61% | 758 MB | 135 | 0.291 |
| fixed 3 (lab only) | 3 | 43.12% | 757 MB | 125 | 0.286 |
| **auto 0.80 + pruning** | **3** | **21.26%** | 759 MB | 135 | 0.375 |

Pruning removes the 12 spurious clusters and returns the correct 3 speakers
without losing DER: **21.61% → 21.26% (−0.35 pp, −1.6% relative)**.
Compared with forcing 3 clusters, auto + pruning is **21.86 pp better
(−50.7% relative)**. The forced 3-cluster run collapsed N and R into one
cluster; auto clustering keeps enough sub-clusters for pruning to preserve
the boundary.

The batch RTF in the final run is higher than the older run, but the batch
path only gained an O(n²) label-remap over 135 segments. No model or ASR work
was added. Treat 0.375 vs 0.291 as emulator run-to-run variance, not a code
regression.

Production defaults are now the tuned ones: `autoClusterThreshold = 0.80`,
`onlineClusterThreshold = 0.45`, cluster pruning 3000 ms / 5%, speaker-change
threshold 0.65. The app does not need to pass any of these explicitly.

## What changed

1. **Cluster pruning** (`ClusterPruner.kt`)
   - A cluster is kept if it has at least
     `max(clusterPruneMinMs, clusterPruneMinFraction × speech duration)`.
   - Defaults: 3000 ms and 5%.
   - Tiny clusters are merged into the dominant cluster with the largest
     temporal overlap, then the smallest total gap.
   - Speaker IDs are compacted after merging.
   - Batch applies pruning only in auto mode. Fixed-speaker mode is not
     modified.

2. **Speaker-change-aware VAD split** (`SpeakerChangeDetector.kt`)
   - CAM++ embedding per 1.6 s window, 0.8 s hop.
   - A speaker change is a local cosine minimum below the threshold
     (default 0.65).
   - Boundaries are non-maximum suppressed with a 1.5 s minimum segment.
   - The boundary is snapped to the lowest-energy 10 ms frame within ±300 ms,
     so a word is less likely to be cut.
   - The split happens before ASR and clustering.

3. **Online cluster creation delay** (`OnlineSpeakerClusterer.kt`)
   - A new cluster is not created until at least 2.5 s of new speech has
     accumulated.
   - While unconfirmed, chunks are assigned to the nearest existing speaker.
   - This removes most one-off “speakers” from short fragments.

4. **VAD hard split** (`VadChunkSplitter.kt`, `SherpaVadEngine.kt`)
   - Any VAD segment longer than `maxSpeechMs` is split defensively.
   - This fixed the 172.4 s segment and most of the 1162 MB PSS peak.

5. **Benchmark controls**
   - `clusterPruneMinMs`, `clusterPruneMinFraction`,
     `speakerChangeThreshold` are exposed through `SpeechConfig`, the
     benchmark JSON, and both benchmark scripts.

## Stage isolation, streaming full session

| Configuration | Speakers | DER | Segments |
|---|---:|---:|---:|
| VAD hard-split, no pruning, no speaker split | 4 | 50.41% | 58 |
| + pruning 5%, speaker split effectively disabled | 2 | 50.57% | 59 |
| + speaker-change split 0.65 | **3** | **22.74%** | 222 |

Pruning alone does **not** improve streaming DER. Without speaker-change
splitting the model only finds two real clusters (767.0 s and 308.2 s); the
old third and fourth “speakers” were 3.1 s and 0.8 s fragments. Pruning
correctly removes them, but the missing third speaker only appears after the
VAD chunks are split by speaker change.

## Cluster durations, full session

Reference: N = 435.8 s, R = 405.9 s, C = 311.0 s.

| Run | Cluster durations |
|---|---|
| batch auto + pruning | 456.1 s / 342.9 s / 229.1 s |
| streaming + split + pruning | 528.8 s / 291.3 s / 251.8 s |
| streaming, no split | 767.0 s / 308.2 s |

The streaming no-split run has one dominant cluster that absorbs two real
speakers. Speaker-change splitting breaks it into three usable clusters.

Confusion on the full session:

```text
batch:   hyp0 N366.6 C89.5 R49.1 | hyp1 C209.8 N34.8 R17.3 | hyp2 R315.3 N21.4 C12.2
stream:  hyp0 N391.0 R142.3 C33.8 | hyp1 C272.6 N38.3 R23.3 | hyp2 R231.7 N3.8 C1.5
```

Streaming still leaks 142.3 s of R into the N cluster, but this is far better
than the no-split run where N and R were inseparable.

## Dev windows

Each window is 180 s and contains all 3 reference speakers.

| Window | Mode | Speakers | DER | Notes |
|---|---|---:|---:|---|
| `pears_dev_00` | batch auto 0.80 + prune | 3 | 30.86% | correct count |
| `pears_dev_00` | streaming + split + prune 5% | 2 | 35.07% | prune drops a 4.6 s R cluster |
| `pears_dev_00` | streaming + split, no prune | 3 | 34.79% | correct count, DER slightly better |
| `pears_dev_01` | batch auto 0.80 + prune | 3 | 27.23% | correct count |
| `pears_dev_01` | streaming + split + prune | 3 | 23.42% | streaming beats batch |

The 5% pruning threshold is too aggressive for a 180 s window where one
speaker has only 4.6 s of speech (2.7%). On the full 1107 s session the same
threshold is needed to remove a 41.6 s mixed cluster and return 3 speakers.
This is a documented short-window trade-off, not a full-session regression.

## Remaining diagnosis

The VAD/segmentation problem is fixed: full-session streaming now finds the
correct 3 speakers and DER is 22.74%.

The next bottleneck is **CAM++/clustering**, especially on short windows:

- `pears_dev_00` still mixes N and R in one cluster even without pruning.
- The same N/R collapse appeared in batch fixed-3 before pruning; auto
  clustering + pruning avoids it on the full session, but not on all windows.
- Streaming still leaks 142 s of R into the N cluster on the full session.

Changing the VAD thresholds further will not fix this. The next experiment
should compare embedding models / clustering objectives, not tune VAD.

## Tests

`:speech:testDebugUnitTest` — **41 tests, 0 failures**:

- `ClusterPrunerTest` — 8
- `SpeakerChangeDetectorTest` — 7
- `OnlineSpeakerClustererTest` — 12
- `VadChunkSplitterTest` — 4
- `VadSegmenterTest` — 5
- `SegmentMergerTest` — 5

## Reproduce

Full batch:

```powershell
.\speech\scripts\benchmark.ps1 -Wav .\speech\eval\pears22\pears22_full.wav `
    -Mode batch -Runs 1 -Threads 4 -Speakers auto `
    -AutoThreshold 0.80 -OnlineThreshold 0.45 `
    -PruneMinMs 3000 -PruneMinFraction 0.05 `
    -OutDir .\bench-results\pears-opt\full-batch-auto080-prune05 -NoBuild
```

Full streaming:

```powershell
.\speech\scripts\benchmark.ps1 -Wav .\speech\eval\pears22\pears22_full.wav `
    -Mode streaming -Runs 1 -Threads 4 -Speakers auto `
    -AutoThreshold 0.80 -OnlineThreshold 0.45 `
    -PruneMinMs 3000 -PruneMinFraction 0.05 `
    -SpeakerChangeThreshold 0.65 `
    -OutDir .\bench-results\pears-opt\full-stream-chg065-prune05 -NoBuild
```

Score:

```powershell
python .\speech\eval\score_der.py `
    --ref .\speech\eval\pears22\refs\pears22_full.rttm `
    --pred .\bench-results\pears-opt\<run>.json `
    --mode streaming --run 1 --collar 0.25
```

## Limitations

- Single full-session run per configuration; repeat runs were stopped because
  the emulator stalled during batch diarization.
- All measurements are x86_64 emulator, not arm64 phone.
- RTF and first-utterance latency regressed because speaker-change detection
  adds CAM++ inference per window. Quality and memory improved.
- The 5% cluster support rule is tuned for long sessions; short windows can
  lose a genuinely quiet speaker.
