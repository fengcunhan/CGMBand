#!/usr/bin/env python3
"""
Print the AuthKey of the Xiaomi band paired with this phone, for AstroBox.

小米运动健康 writes each paired device as a JSON object containing "productName" and
"token" into its debug log once debug logging is on; that token is the AuthKey.
(The field literally named "authKey" in the same log is always null.)

Prerequisites:
  1. USB debugging on, phone connected, `adb devices` lists it.
  2. In 小米运动健康: 设备 → sync; 我的 → 关于 → tap the icon 10+ times → 确定; sync again.

Usage:
  python3 tools/extract_authkey.py            # print every band found
  python3 tools/extract_authkey.py --copy     # also copy the key to the macOS clipboard

Keep the key private: anyone holding it can connect to your band.
"""
import argparse
import re
import shutil
import subprocess
import sys

LOG_DIR = "/sdcard/Android/data/com.mi.health/files/log"
LOG_FILES = ("XiaomiFit.main.log", "XiaomiFit.main.log.bak.1")
PRODUCT_PATTERN = re.compile(r'"productName"\s*:\s*"([^"]+)"')
TOKEN_PATTERN = re.compile(r'"token"\s*:\s*"([0-9a-fA-F]{32})"')
# The token sits in the same JSON object as productName; objects in this log are small.
CONTEXT_CHARS = 3000


def fail(message: str) -> None:
    print(f"错误：{message}", file=sys.stderr)
    sys.exit(1)


def adb(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", *args], capture_output=True, timeout=60)


def ensure_device() -> None:
    if shutil.which("adb") is None:
        fail("找不到 adb。请先安装 Android platform-tools 并加入 PATH。")
    lines = adb("devices").stdout.decode(errors="replace").splitlines()[1:]
    devices = [line for line in lines if line.strip().endswith("device")]
    if not devices:
        fail("没有检测到手机。请连接数据线、打开 USB 调试，并在手机上允许这台电脑调试。")
    if len(devices) > 1:
        fail("检测到多台设备，请只连接一台手机。")


def read_log(name: str) -> str:
    result = adb("exec-out", "cat", f"{LOG_DIR}/{name}")
    return result.stdout.decode("utf-8", errors="replace") if result.returncode == 0 else ""


def find_keys(text: str) -> dict:
    """Map product name -> set of tokens found next to it."""
    found = {}
    for match in PRODUCT_PATTERN.finditer(text):
        start = max(0, match.start() - CONTEXT_CHARS)
        window = text[start: match.end() + CONTEXT_CHARS]
        tokens = {t.lower() for t in TOKEN_PATTERN.findall(window)}
        if tokens:
            found.setdefault(match.group(1), set()).update(tokens)
    return found


def merge(target: dict, source: dict) -> dict:
    merged = {name: set(tokens) for name, tokens in target.items()}
    for name, tokens in source.items():
        merged.setdefault(name, set()).update(tokens)
    return merged


def copy_to_clipboard(key: str) -> bool:
    if shutil.which("pbcopy") is None:
        return False
    subprocess.run(["pbcopy"], input=key.encode(), check=True)
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description="从小米运动健康日志中提取手环 AuthKey")
    parser.add_argument("--copy", action="store_true", help="把唯一找到的 AuthKey 复制到剪贴板（macOS）")
    args = parser.parse_args()

    ensure_device()
    found = {}
    for name in LOG_FILES:
        found = merge(found, find_keys(read_log(name)))

    if not found:
        fail(
            "日志里没有找到 AuthKey。请在小米运动健康里：设备页同步 → 我的 → 关于 → "
            "快速点图标 10 次以上并确定 → 回到设备页再同步一次，然后重新运行本脚本。"
        )

    for name, tokens in sorted(found.items()):
        for token in sorted(tokens):
            print(f"{name}\t{token}")

    all_tokens = {t for tokens in found.values() for t in tokens}
    if args.copy:
        if len(all_tokens) != 1:
            fail("找到多个 AuthKey，请根据设备名手动复制正确的那个。")
        if copy_to_clipboard(all_tokens.pop()):
            print("已复制到剪贴板。", file=sys.stderr)
        else:
            print("当前系统没有 pbcopy，请手动复制上面的 AuthKey。", file=sys.stderr)


if __name__ == "__main__":
    main()
