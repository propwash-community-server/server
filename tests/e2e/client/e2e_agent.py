# Runs inside the test client (Minescript autorun) and serves it to the tests on port 7777: one JSON
# request per line, {"call": "<name>", "args": [...]}, answered by one JSON line, {"result": ...} or
# {"error": "<traceback>"}. A name is one of the functions below or any Minescript function.
import dataclasses
import json
import socketserver
import threading
import traceback
from pathlib import Path

import minescript
from java import JavaClass

# Chat comes from the game log: Minescript's chat listener misses replies to the script's own commands.
log = open(Path(__file__).resolve().parent.parent / "logs/latest.log", errors="replace")
log.seek(0, 2)
log_lock = threading.Lock()


def chat_lines():
    """Chat lines shown since the last call."""
    with log_lock:
        lines = log.readlines()
    return [l.split(" [CHAT] ", 1)[1].rstrip("\n").replace("\\n", "\n") for l in lines if " [CHAT] " in l]


def hidden_field(target, name):
    field = target.getClass().getDeclaredField(name)
    field.setAccessible(True)
    value = field.get(target)
    return None if value is None else str(value.getString())


def title():
    """The title and subtitle on screen, None when there is none."""
    hud = JavaClass("net.minecraft.client.Minecraft").getInstance().gui.hud
    return {"title": hidden_field(hud, "title"), "subtitle": hidden_field(hud, "subtitle")}


def tab_players():
    """Names of the players in the tab list."""
    connection = JavaClass("net.minecraft.client.Minecraft").getInstance().getConnection()
    return [str(p.getProfile().name()) for p in connection.getListedOnlinePlayers()]


CALLS = {f.__name__: f for f in (chat_lines, title, tab_players)}


def plain(value):
    if dataclasses.is_dataclass(value):
        return dataclasses.asdict(value)
    return str(value)


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        for line in self.rfile:
            request = json.loads(line)
            try:
                function = CALLS.get(request["call"]) or getattr(minescript, request["call"])
                reply = {"result": function(*request.get("args", []))}
            except Exception:
                reply = {"error": traceback.format_exc()}
            self.wfile.write(json.dumps(reply, default=plain).encode() + b"\n")


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


try:
    server = Server(("0.0.0.0", 7777), Handler)
except OSError:
    # Minescript autoruns the agent again on every server switch; the first one is still serving.
    raise SystemExit
# Minescript stops scripts on a server switch unless they listen for world events.
world_events = minescript.EventQueue()
world_events.register_world_listener()
server.serve_forever()
