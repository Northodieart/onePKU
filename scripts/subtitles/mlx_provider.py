"""OnePKU provider v1: request JSON path -> atomic result JSON.

Receives only local audio/model paths, never school cookies. MLX Whisper and
MLX Audio share the same cue contract. VAD prevents captions during breaks.
"""
import json
import os
import sys
import threading
import time
import wave
from pathlib import Path

os.environ.setdefault("HF_HUB_OFFLINE", "1")
parent = os.getppid()


def watch_parent():
    while True:
        time.sleep(2)
        if os.getppid() != parent:
            os._exit(1)


threading.Thread(target=watch_parent, daemon=True).start()
import numpy as np
import torch
from silero_vad import get_speech_timestamps, load_silero_vad

torch.set_num_threads(1)
request = json.load(open(sys.argv[1], encoding="utf-8"))
with wave.open(request["audio"]) as source:
    if source.getframerate() != 16000 or source.getnchannels() != 1 or source.getsampwidth() != 2:
        raise ValueError("Expected mono 16 kHz PCM16 audio")
    audio = np.frombuffer(source.readframes(source.getnframes()), dtype=np.int16).astype(np.float32) / 32768
speech = get_speech_timestamps(torch.from_numpy(audio), load_silero_vad(), sampling_rate=16000, return_seconds=True)

def atomic_json(path, value):
    temporary = str(path) + ".part"
    with open(temporary, "w", encoding="utf-8") as file:
        json.dump(value, file, ensure_ascii=False)
    os.replace(temporary, path)


# Silent chunks still advance durable coverage without loading the ASR model.
if not speech:
    atomic_json(request["output"], {"version": 1, "cues": []})
    sys.exit(0)
trim_start = max(0.0, float(request.get("trim_start", 0)))
trim_end = min(len(audio) / 16000, float(request.get("trim_end", len(audio) / 16000)))
engine = request.get("engine", "mlx-whisper")
if engine == "mlx-audio":
    from mlx_audio.stt import load
    model = load(request["model"])

    def recognize(samples):
        return model.generate(samples, language=request.get("language", "zh"), word_timestamps=True,
                              condition_on_previous_text=False, verbose=False).segments
elif engine == "mlx-whisper":
    import mlx_whisper

    def recognize(samples):
        return mlx_whisper.transcribe(samples, path_or_hf_repo=request["model"], language=request.get("language", "zh"),
                                      word_timestamps=True, condition_on_previous_text=False, verbose=False)["segments"]
else:
    raise ValueError("Unknown built-in recognition engine")


def speech_overlap(start, end):
    return sum(max(0, min(end, span["end"]) - max(start, span["start"])) for span in speech if span["end"] > start and span["start"] < end)


cues = []
duration = len(audio) / 16000
# Chunk boundaries have context on both sides. Each word belongs to one chunk
# according to its midpoint, so the final track has no duplicate overlap.
for base in range(0, int(duration) + 1, 180):
    stop = min(base + 180, duration)
    start = max(0, base - 1)
    if stop <= base:
        continue
    if speech_overlap(base, stop) >= 1:
        samples = audio[int(start * 16000):int(min(stop + 1, duration) * 16000)]
        for segment in recognize(samples):
            if segment.get("avg_logprob", 0) < -1.0:
                continue
            words = segment.get("words") or [{"word": segment["text"], "start": segment["start"], "end": segment["end"]}]
            group = []

            def flush():
                if not group:
                    return
                a = max(trim_start, group[0][0]); b = min(trim_end, group[-1][1])
                text = "".join(word[2] for word in group).strip()
                if b > a and text and speech_overlap(a, b) >= (b - a) * 0.25:
                    cues.append({"start": round(a, 3), "end": round(b, 3), "text": text})
                group.clear()

            for word in words:
                a, b = start + word["start"], start + word["end"]
                if not max(base, trim_start) <= (a + b) / 2 < min(stop, trim_end):
                    continue
                text = word["word"]
                if group and (len("".join(w[2] for w in group)) + len(text) > 38 or b - group[0][0] > 7 or a - group[-1][1] > 0.8):
                    flush()
                group.append((a, b, text))
                if text.rstrip().endswith(("。", "？", "！", "?", "!")):
                    flush()
            flush()
    atomic_json(Path(request["output"]).with_name("progress.json"), {"completed": stop, "total": duration})
atomic_json(request["output"], {"version": 1, "cues": cues})
