#!/usr/bin/env python3
"""Run Gradle using JDK 25 and the cloud's existing proxy and trusted certificates."""
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit

root = Path(__file__).resolve().parents[1]
environment = os.environ.copy()
if "JAVA_HOME" not in environment:
    candidates = sorted(Path("/workspace/toolchains").glob("jdk-25*"))
    if candidates:
        environment["JAVA_HOME"] = str(candidates[-1])
if Path("/workspace").exists():
    environment.setdefault("GRADLE_USER_HOME", "/workspace/.gradle")
options = []
proxy = urlsplit(environment.get("HTTPS_PROXY", environment.get("https_proxy", "")))
if proxy.hostname and not proxy.username:
    for scheme in ("http", "https"):
        options += [f"-D{scheme}.proxyHost={proxy.hostname}", f"-D{scheme}.proxyPort={proxy.port or 80}"]
    options += ["-Dhttp.nonProxyHosts=localhost|127.*"]
    trust = Path("/etc/ssl/certs/java/cacerts")
    if trust.is_file():
        options += [f"-Djavax.net.ssl.trustStore={trust}"]
if options:
    environment["JAVA_TOOL_OPTIONS"] = " ".join([environment.get("JAVA_TOOL_OPTIONS", ""), *options]).strip()
result = subprocess.run([str(root / "gradlew"), *(sys.argv[1:] or [":core:test", ":mod:build"]), "--console=plain"], cwd=root, env=environment)
sys.exit(result.returncode)
