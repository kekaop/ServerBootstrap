#!/usr/bin/env python3
"""Create/update a NEW, marked sandbox. Never point this at a real server."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile

p = argparse.ArgumentParser()
p.add_argument("directory", type=Path)
p.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "")))
p.add_argument("--port", type=int, default=18443)
p.add_argument("--version", choices=("v1", "v2"), default="v1")
p.add_argument("--update", action="store_true")
a = p.parse_args()
base = a.directory.resolve()
if a.update:
    if not (base / ".serverbootstrap-sandbox").is_file():
        p.error("Update requires a marked sandbox created by this script")
else:
    if base.exists():
        p.error("Target must not exist; refusing to touch an existing directory")
    base.mkdir(parents=True)
    (base / ".serverbootstrap-sandbox").write_text("ServerBootstrap test sandbox\n")
data = base / "server" / "plugins" / "ServerBootstrap"
data.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(base / "profile.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    archive.writestr("plugins/Demo/config.yml", f'message: "Sandbox {a.version}"\n')
digest = hashlib.sha256((base / "profile.zip").read_bytes()).hexdigest()
config = f"""config-version: 1
profiles:
  demo:
    source:
      type: https
      url: https://localhost:{a.port}/profile.zip
    version: "{a.version}"
    sha256: "{digest}"
    archive-root: "."
    apply:
      include: [plugins]
      required-files: [plugins/Demo/config.yml]
    restart: false
"""
(data / "config.yml").write_text(config, encoding="utf-8")
store = base / "sandbox.p12"
if not store.exists():
    keytool = a.java_home / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")
    subprocess.run([str(keytool), "-genkeypair", "-alias", "sandbox", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "30", "-storetype", "PKCS12", "-keystore", str(store),
                    "-storepass", "sandbox-only", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1"], check=True)
print(f"Sandbox: {base}")
print(f"SHA-256: {digest}")
print(f"Profile: demo {a.version}; HTTPS port: {a.port}")
