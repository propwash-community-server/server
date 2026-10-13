"""The local stack, its servers (through rcon) and the test client (through its agent)."""

import json
import re
import socket
import subprocess
import time
import tomllib
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
LOGS = HERE / "logs"
COMPOSE = ["docker", "compose", "-f", str(ROOT / "local/compose.yaml")]
CONTAINER = "propwash-local"
CLIENT = "propwash-e2e-client"
NETWORK = "propwash-local_default"
AGENT_PORT = 7777


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout


def eventually(check, timeout=10, interval=0.25, message="condition"):
    """Calls check until it returns something truthy, and returns that."""
    deadline = time.monotonic() + timeout
    while True:
        value = check()
        if value:
            return value
        if time.monotonic() > deadline:
            raise AssertionError(f"timed out after {timeout}s waiting for {message}")
        time.sleep(interval)


# --- Stack --------------------------------------------------------------------


def deploy():
    subprocess.run(["uv", "run", "pyinfra", "deploy/inventory.py", "deploy/deploy.py", "--limit", "test", "-y"],
                   cwd=ROOT, check=True, stdout=subprocess.DEVNULL)


def fresh_stack():
    """Wipes the local stack and deploys the test inventory, twice (see docs)."""
    subprocess.run([*COMPOSE, "down", "-v"], check=True)
    subprocess.run([*COMPOSE, "up", "-d", "--build"], check=True)
    deploy()
    deploy()


def servers():
    return [p.parent.name for p in sorted(ROOT.glob("servers/*/server.toml")) if p.parent.name != "velocity"]


def wait_for_servers(timeout=300):
    for name in servers():
        eventually(lambda: Server(name).ready(), timeout=timeout, interval=2, message=f"{name} to start")


def wait_for_warmup():
    """mods/warmup turns players away for two minutes after the proxy starts."""
    started = int(run("docker", "exec", CONTAINER, "systemctl", "show", "mc@velocity", "--value",
                      "-p", "ActiveEnterTimestampMonotonic")) / 1e6
    uptime = float(run("docker", "exec", CONTAINER, "cat", "/proc/uptime").split()[0])
    time.sleep(max(0, started + 125 - uptime))


# --- Servers ------------------------------------------------------------------


class Server:
    def __init__(self, name):
        self.name = name

    def rcon(self, command):
        return run("docker", "exec", CONTAINER, "mc-rcon", self.name, command).strip()

    def ready(self):
        try:
            self.rcon("list")
            return True
        except subprocess.CalledProcessError:
            return False

    def online(self):
        # Vanilla: "There are 1 of a max of 20 players online: a, b". EssentialsX (hub): one
        # "<group>: a, b" line per group.
        out = re.sub(r"§.", "", self.rcon("list"))
        names = []
        for line in out.splitlines():
            if ":" in line:
                names += [n.strip() for n in line.split(":", 1)[1].split(",") if n.strip()]
        return names

    def check(self, condition):
        """True if `execute <condition>` passes, e.g. `in maps:x if block 1 2 3 minecraft:stone`."""
        return self.rcon(f"execute {condition}").startswith("Test passed")


HUB, FPV, PLOTS = Server("hub"), Server("fpv"), Server("plots")


def luckperms(command, server=HUB):
    """A LuckPerms command. LuckPerms shares its data over the network, but other servers see a change
    only a few seconds later: run it on the server where it is needed."""
    server.rcon(f"lp {command}")
    server.rcon("lp networksync")


# --- Client -------------------------------------------------------------------


def client_mods():
    client = tomllib.loads((HERE / "client.toml").read_text())
    fpv = tomllib.loads((ROOT / "servers/fpv/server.toml").read_text())
    mods = [url for url in fpv["mods"] for name in client["from_fpv"] if unquote(url.rsplit("/", 1)[1]).startswith(name)]
    assert len(mods) == len(client["from_fpv"]), f"not all of {client['from_fpv']} found in servers/fpv/server.toml"
    return client["headlessmc"], mods + client["mods"]


def build_client():
    headlessmc, mods = client_mods()
    subprocess.run(["docker", "build", "-q", "-t", CLIENT, "--build-arg", f"HEADLESSMC={headlessmc}",
                    "--build-arg", f"MODS={' '.join(mods)}", str(HERE / "client")], check=True, stdout=subprocess.DEVNULL)


def start_client(name="Offline", port=AGENT_PORT, wait=True):
    """Starts a client that joins as `name`; each client needs its own agent port. Without `wait`, for a
    client that won't get in, it returns at once."""
    container = f"{CLIENT}-{name.lower()}"
    subprocess.run(["docker", "rm", "-f", container], capture_output=True)
    run("docker", "run", "-d", "--name", container, "--network", NETWORK, "-p", f"127.0.0.1:{port}:{AGENT_PORT}",
        CLIENT, "vps:25565", name)
    if wait:
        return eventually(lambda: Player.connect(port), timeout=180, interval=1, message=f"{name} to join")


def stop_client(name="Offline"):
    container = f"{CLIENT}-{name.lower()}"
    LOGS.mkdir(exist_ok=True)
    (LOGS / f"client-{name.lower()}.log").write_text(subprocess.run(["docker", "logs", container], text=True,
                                                                    capture_output=True, errors="replace").stdout)
    subprocess.run(["docker", "rm", "-f", container], capture_output=True)


class Player:
    """The test client, Playwright-style: actions, plus expect_* methods that wait."""

    def __init__(self, sock):
        self.file = sock.makefile("rwb")
        self.name = self.call("player_name")
        self.chat = []  # Chat lines read so far, oldest first; see clear_chat.

    @classmethod
    def connect(cls, port=AGENT_PORT):
        try:
            return cls(socket.create_connection(("127.0.0.1", port), timeout=30))
        except OSError:
            return None

    def call(self, name, *args):
        self.file.write(json.dumps({"call": name, "args": args}).encode() + b"\n")
        self.file.flush()
        line = self.file.readline()
        if not line:
            raise ConnectionError("the client agent closed the connection")
        reply = json.loads(line)
        if "error" in reply:
            raise RuntimeError(f"{name}{args} failed in the client:\n{reply['error']}")
        return reply["result"]

    # Actions

    def command(self, command):
        self.call("execute", command)

    def go(self, server, timeout=30):
        """Switches to the server (/hub or /server) and waits until the client is in its world.
        Switching again before that can make the proxy drop the client."""
        if self.name in server.online():
            return
        world = self.world()
        self.command("/hub" if server is HUB else f"/server {server.name}")
        self.expect_arrival(server, world, timeout)

    def look_at(self, x, y, z):
        self.call("player_look_at", x, y, z)

    def attack(self, seconds=0.5):
        """Holds the attack button: in creative, breaks the block looked at."""
        self.call("player_press_attack", True)
        time.sleep(seconds)
        self.call("player_press_attack", False)

    def use(self):
        """Clicks the use button: places the held block or item where the player looks."""
        self.call("player_press_use", True)
        time.sleep(0.1)
        self.call("player_press_use", False)

    def select_slot(self, slot):
        self.call("player_inventory_select_slot", slot)

    # State

    def dimension(self):
        return self.call("world_info")["dimension"]

    def position(self):
        return self.call("player_position")

    def title(self):
        return self.call("title")

    def tab_players(self):
        return self.call("tab_players")

    def tab_names(self):
        return self.call("tab_names")

    def world(self):
        """The world the client is in; a server switch replaces it."""
        return self.call("world_state")["world"]

    def clear_chat(self):
        self.call("chat_lines")
        self.chat = []

    # Expectations

    def expect_chat(self, pattern, timeout=10):
        """Waits for a chat line (read since the last clear_chat) matching the regex, and returns the match."""
        def find():
            self.chat += self.call("chat_lines")
            return next((m for line in self.chat if (m := re.search(pattern, line))), None)
        try:
            return eventually(find, timeout=timeout, message=f"chat matching {pattern!r}")
        except AssertionError as e:
            raise AssertionError(f"{e}; chat was: {self.chat}") from None

    def expect_arrival(self, server, world, timeout=30):
        """Waits until the client has left `world` (from world()) and finished loading into the server."""
        def arrived():
            state = self.call("world_state")
            return state["world"] not in (None, world) and state["screen"] is None and self.name in server.online()
        eventually(arrived, timeout=timeout, message=f"{self.name} in {server.name}")

    def expect_dimension(self, dimension, timeout=10):
        eventually(lambda: self.dimension() == dimension, timeout=timeout, message=f"dimension {dimension}")

    def expect_title(self, title, subtitle, timeout=10):
        expected = {"title": title, "subtitle": subtitle}
        try:
            eventually(lambda: self.title() == expected, timeout=timeout, message=f"title {expected}")
        except AssertionError as e:
            raise AssertionError(f"{e}; title was {self.title()}") from None
