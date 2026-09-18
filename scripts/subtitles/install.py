"""Explicit, optional subtitle setup. Never changes an existing environment.

Run install.sh from a downloaded OnePKU source checkout. Only this installer
downloads dependencies/models; runtime inference remains offline.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import uuid

HERE = Path(__file__).resolve().parent
MODEL = "mlx-community/belle-whisper-large-v3-turbo-zh-8bit"
REVISION = "cb886304c3822efe538ac975446080fe370cb243"
DEFAULT_ROOT = Path.home() / "Library/Application Support/me.petertian.OnePKU"


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def check(config):
    for key in ("python", "ffmpeg"):
        path = Path(config.get(key, ""))
        if not path.is_absolute() or not path.is_file() or not os.access(path, os.X_OK):
            raise RuntimeError(f"{key} 不可用；原配置保持不变。")
    print("正在离线检查字幕组件（会短暂加载模型）…", flush=True)
    run([config["python"], HERE / "check_runtime.py"],
        input=json.dumps(config), text=True, timeout=180)


def publish(config_path, config, expected):
    # Do not overwrite a concurrent settings edit. The old runtime remains intact.
    current = config_path.read_bytes() if config_path.exists() else None
    if current != expected:
        raise RuntimeError("配置已被其他操作更改，未覆盖；请重新运行安装工具。")
    if current is not None:
        backup = config_path.with_name(f"subtitles-provider.backup-{uuid.uuid4().hex}.json")
        with backup.open("xb") as file:
            os.chmod(backup, 0o600)
            file.write(current)
    fd, temp = tempfile.mkstemp(prefix=".subtitle-config-", dir=config_path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as file:
            json.dump(config, file, ensure_ascii=False, indent=2)
            file.write("\n")
            file.flush()
            os.fsync(file.fileno())
        os.replace(temp, config_path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def install(args):
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise RuntimeError("自动安装目前仅支持 Apple Silicon Mac；字幕导入不受此限制。")
    root = args.data_dir.expanduser().resolve()
    config_path = root / "subtitles-provider.json"
    original = config_path.read_bytes() if config_path.exists() else None
    if args.check:
        if original is None:
            raise RuntimeError("尚未安装字幕组件。运行 bash scripts/subtitles/install.sh 安装。")
        check(json.loads(original))
        return
    if original is not None and not args.replace:
        check(json.loads(original))
        print("已有配置可用，直接复用；没有安装或更改任何文件。")
        return
    uv = shutil.which("uv")
    ffmpeg = shutil.which("ffmpeg")
    if not uv or not ffmpeg:
        raise RuntimeError("请先安装缺少的工具：brew install uv ffmpeg，然后重试。")
    if int(platform.mac_ver()[0].split(".")[0]) < 14:
        raise RuntimeError("此字幕组件需要 macOS 14 或以上。")
    root.mkdir(parents=True, exist_ok=True)
    lock_dir = root / "subtitle-locks"
    lock_dir.mkdir(mode=0o700, exist_ok=True)
    lock_path = lock_dir / (hashlib.sha256(b"recognition-runtime").hexdigest() + ".lock")
    with lock_path.open("a+") as lock:
        os.chmod(lock_path, 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("字幕正在生成或另一安装正在运行，请结束后再试。")
        # A new versioned directory avoids modifying an environment in use and
        # keeps venv absolute paths valid (virtual environments cannot be moved).
        runtime = root / "subtitle-runtimes" / ("belle-" + uuid.uuid4().hex[:12])
        print("将安装独立 Python 3.12 环境和 Belle 中文模型；模型约 864 MB，另需运行依赖空间。", flush=True)
        print("安装失败可重新运行；已下载的模型和包会复用缓存，原有字幕不变。", flush=True)
        run([uv, "venv", "--python", "3.12", "--managed-python", runtime])
        python = runtime / "bin/python"
        run([uv, "pip", "sync", "--python", python, HERE / "requirements.lock"])
        print("正在取得固定版本模型…", flush=True)
        download = "from huggingface_hub import snapshot_download; import sys; print(snapshot_download(sys.argv[1], revision=sys.argv[2]))"
        env = dict(os.environ, HF_HUB_DISABLE_TELEMETRY="1")
        env.pop("HF_HUB_OFFLINE", None)
        result = run([python, "-c", download, MODEL, REVISION],
                     stdout=subprocess.PIPE, text=True, env=env)
        model = Path(result.stdout.strip().splitlines()[-1])
        if not model.is_dir():
            raise RuntimeError("模型下载未完成，原配置保持不变。")
        config = {"label": "Belle Whisper 中文 · 本机", "engine": "mlx-audio",
                  "python": str(python), "model": str(model), "ffmpeg": ffmpeg}
        check(config)
        publish(config_path, config, original)
        print("字幕组件已安装。回到 OnePKU 设置刷新，或重新打开播放器，即可生成字幕。")


def main():
    parser = argparse.ArgumentParser(description="OnePKU 可选本机字幕组件（Apple Silicon）")
    parser.add_argument("--check", action="store_true", help="仅离线检查已有配置，不安装或改写")
    parser.add_argument("--replace", action="store_true", help="新建环境并替换配置；保留旧配置备份与环境")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_ROOT,
                        help="自定义安装目录，供隔离验收使用")
    args = parser.parse_args()
    if args.check and args.replace:
        parser.error("--check 与 --replace 不能同时使用")
    try:
        install(args)
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"字幕组件未启用：{error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr[-3000:], file=sys.stderr)
        print("已有配置未被安装步骤改写；可检查后重试，或用 --replace 创建新环境。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
