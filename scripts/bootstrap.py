#!/usr/bin/env python3
"""Prepare a cloud checkout without changing dependency versions or trust policy."""
import hashlib
from pathlib import Path
import subprocess
import sys
import tarfile
import urllib.request

root = Path(__file__).resolve().parents[1]
tools = Path("/workspace/toolchains")
tools.mkdir(parents=True, exist_ok=True)
if not list(tools.glob("jdk-25*/bin/java")):
    url = "https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz"
    expected = urllib.request.urlopen(url + ".sha256", timeout=30).read().decode().split()[0]
    archive = tools / "minebridge-jdk25.tar.gz"
    digest = hashlib.sha256()
    with urllib.request.urlopen(url, timeout=60) as response, archive.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            output.write(chunk)
    if digest.hexdigest() != expected:
        archive.unlink()
        raise RuntimeError("Official JDK checksum mismatch")
    with tarfile.open(archive) as content:
        content.extractall(tools, filter="data")
    archive.unlink()
subprocess.run([sys.executable, "-m", "venv", str(root / ".venv")], check=True)
subprocess.run([str(root / ".venv/bin/python"), "-m", "pip", "install", "--no-cache-dir", "-r", str(root / "python/requirements.txt")], check=True)
subprocess.run([sys.executable, str(root / "scripts/dev.py"), ":core:test", ":mod:build"], check=True)
