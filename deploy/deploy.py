"""
Velocity + Paper/Fabric backends on one box, one folder per server under servers/.

Safe to re-run: it only changes what differs, and never touches worlds,
whitelists, ops or any server.properties setting except the ones it manages.
"""

import os
import subprocess
import tomllib
from io import StringIO
from pathlib import Path
from urllib.parse import unquote

from pyinfra import host
from pyinfra.api import FactBase
from pyinfra.facts.files import File, FindFiles
from pyinfra.operations import apt, files, server, systemd
from pyinfra.operations.util import any_changed

ROOT = Path(__file__).resolve().parent.parent
MC = "mc"
SERVERS = f"/home/{MC}/servers"
OWNER = {"user": MC, "group": MC}
LOCAL = host.data.get("local", False)

configs = {p.parent.name: tomllib.loads(p.read_text()) for p in sorted(ROOT.glob("servers/*/server.toml"))}
velocity = configs.pop("velocity")
backends = configs


def read_secret(name, kind="binary"):
    # Local runs decrypt the dummies in local/secrets/ with the committed local/age.key.
    folder, env = (ROOT / "local/secrets", {**os.environ, "SOPS_AGE_KEY_FILE": str(ROOT / "local/age.key")}) if LOCAL else (ROOT / "secrets", None)
    return subprocess.run(
        ["sops", "-d", "--input-type", kind, "--output-type", kind, str(folder / f"{name}.sops")],
        check=True, capture_output=True, text=True, env=env,
    ).stdout.strip()


secret = read_secret("forwarding.secret")


def filename(url):
    return unquote(url.split("?")[0].rsplit("/", 1)[-1])


def start_script(path, jar, ram, args=""):
    script = (
        "#!/bin/sh\n"
        "# Managed by pyinfra - edit servers/<name>/server.toml instead.\n"
        f"exec java -Xms{ram} -Xmx{ram} -jar {jar} {args}\n"
    )
    return files.put(name=f"start script {path}", src=StringIO(script), dest=path, mode="755", **OWNER)


class PaperVelocityOk(FactBase):
    def command(self, server_dir):
        return f"/usr/local/bin/paper-velocity --check {server_dir} 2>/dev/null || true"

    def process(self, output):
        return bool(output) and output[0].strip() == "ok"


def jar_folder(label, folder, urls):
    # Download listed jars, delete any other jar in the folder.
    wanted = {filename(u): u for u in urls}
    ops = [files.directory(name=f"{label} folder", path=folder, **OWNER)]
    for jar, url in wanted.items():
        ops.append(files.download(name=f"{label} {jar}", src=url, dest=f"{folder}/{jar}", **OWNER))
    for path in host.get_fact(FindFiles, path=folder, maxdepth=1, fname="*.jar") or []:
        if Path(path).name not in wanted:
            ops.append(files.file(name=f"{label} remove {Path(path).name}", path=path, present=False))
    return ops


def extra_files(name, d):
    # servers/<name>/files/ is copied as is into the server folder.
    src = ROOT / "servers" / name / "files"
    return [
        files.put(name=f"{name}: {p.relative_to(src).as_posix()}", src=str(p), dest=f"{d}/{p.relative_to(src).as_posix()}", **OWNER)
        for p in sorted(src.rglob("*")) if p.is_file()
    ]


def service(name, changes):
    # Restart only if already running and something changed, then make sure it's up.
    systemd.service(
        name=f"{name}: restart if changed",
        service=f"mc@{name}",
        running=None,
        restarted=True,
        daemon_reload=True,
        _if=any_changed(*changes),
    )
    systemd.service(name=f"{name}: running", service=f"mc@{name}", running=True, enabled=True, daemon_reload=True)


# --- Base system -----------------------------------------------------------

apt.packages(
    name="Install Java and tools",
    packages=["openjdk-25-jre-headless", "tmux", "ufw", "curl", "python3-yaml"],
    update=True,
    cache_time=3600,
)

server.user(name="mc user", user=MC, home=f"/home/{MC}", shell="/bin/bash")

if not LOCAL:
    server.shell(
        name="Firewall: SSH + Velocity only",
        commands=["ufw allow 22/tcp", "ufw allow 25565/tcp", "ufw --force enable"],
    )

    # Additive for now: switch to delete_keys=True once every current key is in access/.
    keys = [p.read_text().strip() for p in sorted(ROOT.glob("access/*.pub"))]
    if keys:
        server.user_authorized_keys(name="root SSH keys", user="root", public_keys=keys)

files.put(name="systemd unit mc@.service", src=str(ROOT / "deploy/files/mc@.service"), dest="/etc/systemd/system/mc@.service")
files.put(name="mc-console helper", src=str(ROOT / "deploy/files/mc-console"), dest="/usr/local/bin/mc-console", mode="755")
files.put(name="paper-velocity helper", src=str(ROOT / "deploy/files/paper-velocity"), dest="/usr/local/bin/paper-velocity", mode="755")

files.directory(name="servers folder", path=SERVERS, **OWNER)

# --- Velocity (first: Paper backends read its forwarding.secret) ------------

v = f"{SERVERS}/velocity"
files.directory(name="velocity folder", path=v, **OWNER)

changes = [
    files.download(name="velocity jar", src=velocity["url"], dest=f"{v}/{velocity['jar']}", **OWNER),
    start_script(f"{v}/start.sh", velocity["jar"], velocity["ram"]),
    files.put(name="velocity: stop command", src=StringIO("STOP_CMD=end\n"), dest=f"{v}/service.env", **OWNER),
    files.template(
        name="velocity.toml",
        src=str(ROOT / "servers/velocity/velocity.toml.j2"),
        dest=f"{v}/velocity.toml",
        backends=backends,
        try_order=velocity["try"],
        **OWNER,
    ),
    files.put(name="forwarding.secret", src=StringIO(secret), dest=f"{v}/forwarding.secret", mode="600", **OWNER),
    *jar_folder("velocity: plugin", f"{v}/plugins", velocity.get("plugins", [])),
    *extra_files("velocity", v),
]

service("velocity", changes)

# --- Backends --------------------------------------------------------------

for name, cfg in backends.items():
    d = f"{SERVERS}/{name}"
    kind = cfg["type"]
    files.directory(name=f"{name}: folder", path=d, **OWNER)

    changes = [
        files.download(name=f"{name}: server jar", src=cfg["url"], dest=f"{d}/{cfg['jar']}", **OWNER),
        start_script(f"{d}/start.sh", cfg["jar"], cfg["ram"], "nogui"),
        files.put(name=f"{name}: stop command", src=StringIO("STOP_CMD=stop\n"), dest=f"{d}/service.env", **OWNER),
        files.put(name=f"{name}: eula", src=StringIO("eula=true\n"), dest=f"{d}/eula.txt", **OWNER),
    ]
    if kind == "fabric":
        changes += jar_folder(f"{name}: mod", f"{d}/mods", cfg.get("mods", []))
    else:
        changes += jar_folder(f"{name}: plugin", f"{d}/plugins", cfg.get("plugins", []))
    changes += extra_files(name, d)

    if cfg.get("new_world") and host.get_fact(File, path=f"{d}/server.properties") is None:
        props = "".join(f"{k}={v}\n" for k, v in cfg["new_world"].items())
        changes.append(files.put(name=f"{name}: new world settings", src=StringIO(props), dest=f"{d}/server.properties", **OWNER))

    # Created as mc first: files.line would otherwise create them as root.
    files.file(name=f"{name}: server.properties", path=f"{d}/server.properties", **OWNER)
    for key, value in {"server-ip": "127.0.0.1", "server-port": cfg["port"], "online-mode": "false"}.items():
        changes.append(
            files.line(name=f"{name}: {key}", path=f"{d}/server.properties", line=f"^{key}=.*", replace=f"{key}={value}")
        )

    files.directory(name=f"{name}: config folder", path=f"{d}/config", **OWNER)
    if kind == "fabric":
        files.file(name=f"{name}: FabricProxy-Lite.toml", path=f"{d}/config/FabricProxy-Lite.toml", **OWNER)
        changes.append(
            files.line(
                name=f"{name}: Velocity secret",
                path=f"{d}/config/FabricProxy-Lite.toml",
                line="^secret *=.*",
                replace=f'secret = "{secret}"',
            )
        )
    elif not host.get_fact(PaperVelocityOk, server_dir=d):
        changes.append(server.shell(name=f"{name}: Velocity forwarding", commands=[f"/usr/local/bin/paper-velocity {d}"]))

    # files.line creates/edits as root, so hand everything back to mc.
    files.directory(name=f"{name}: ownership", path=d, recursive=True, **OWNER)

    service(name, changes)
