"""Offline check in the configured interpreter; does not read campus data."""
import json
import os
import subprocess
import sys

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
config = json.load(sys.stdin)
subprocess.run([config["ffmpeg"], "-version"], check=True, stdout=subprocess.DEVNULL,
               stderr=subprocess.DEVNULL, timeout=15)
import numpy as np
import torch
from silero_vad import load_silero_vad

torch.set_num_threads(1)
vad = load_silero_vad()
vad(torch.zeros(512), 16000)
if config.get("adapter"):
    raise RuntimeError("自定义适配器请保留原配置并单独验证；安装工具仅检查内置识别引擎")
if config.get("engine", "mlx-whisper") == "mlx-audio":
    from mlx_audio.stt import load
    model = load(config["model"])
    # Exercise the actual decoder/tokenizer, not just the file/import checks.
    model.generate(np.zeros(16000, dtype=np.float32), language="zh",
                   word_timestamps=True, condition_on_previous_text=False, verbose=False)
elif config.get("engine", "mlx-whisper") == "mlx-whisper":
    import mlx_whisper
    mlx_whisper.transcribe(np.zeros(16000, dtype=np.float32),
                           path_or_hf_repo=config["model"], language="zh",
                           word_timestamps=True, condition_on_previous_text=False, verbose=False)
else:
    raise RuntimeError("不支持的识别引擎")
print("Python、ffmpeg、人声检测、模型加载与识别检查通过。")
