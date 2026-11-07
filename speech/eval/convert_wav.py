"""Convert an arbitrary PCM WAV to canonical 16 kHz mono PCM16.

Usage:
  python convert_wav.py input.wav output.wav [max_seconds]

Canonical output: RIFF/WAVE, fmt chunk 16 bytes at offset 12,
data chunk at offset 36, 44-byte header, mono, 16000 Hz, PCM16.
"""
from __future__ import annotations

import sys
import wave
from pathlib import Path

import numpy as np


def read_pcm16(path: Path) -> tuple[np.ndarray, int, int]:
    with wave.open(str(path), "rb") as wf:
        channels = wf.getnchannels()
        rate = wf.getframerate()
        width = wf.getsampwidth()
        frames = wf.getnframes()
        raw = wf.readframes(frames)
    if width != 2:
        raise SystemExit(f"expected PCM16, got {width * 8}-bit: {path}")
    data = np.frombuffer(raw, dtype="<i2").astype(np.float32)
    # A truncated download can end in the middle of an interleaved frame.
    data = data[: data.size - (data.size % channels)]
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    return data, rate, channels


def resample_linear(samples: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    if src_rate == dst_rate:
        return samples
    duration = samples.size / src_rate
    out_size = int(round(duration * dst_rate))
    if out_size <= 0:
        return np.zeros(0, dtype=np.float32)
    src_positions = np.arange(out_size, dtype=np.float64) * (src_rate / dst_rate)
    left = np.floor(src_positions).astype(np.int64)
    right = np.minimum(left + 1, samples.size - 1)
    frac = (src_positions - left).astype(np.float32)
    return samples[left] * (1.0 - frac) + samples[right] * frac


def write_canonical(path: Path, samples: np.ndarray, rate: int = 16000) -> None:
    pcm = np.clip(np.round(samples), -32768, 32767).astype("<i2").tobytes()
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(pcm)


def main() -> None:
    if len(sys.argv) not in (3, 4):
        raise SystemExit(__doc__)
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2])
    max_seconds = float(sys.argv[3]) if len(sys.argv) == 4 else None

    samples, rate, channels = read_pcm16(src)
    if max_seconds is not None:
        samples = samples[: int(round(max_seconds * rate))]
    samples = resample_linear(samples, rate, 16000)
    dst.parent.mkdir(parents=True, exist_ok=True)
    write_canonical(dst, samples)
    duration = samples.size / 16000
    print(f"{src} -> {dst}: {channels}ch {rate}Hz -> 1ch 16000Hz, {duration:.2f}s")


if __name__ == "__main__":
    main()
