import re
import tomllib

import pytest

import harness
from harness import FPV, HUB, ROOT, eventually, luckperms

MAPS = {p.stem: tomllib.loads(p.read_text()) for p in sorted(ROOT.glob("servers/fpv/maps/*.toml"))}
# The first map. The tests stand on a 5x5 stone platform at x/z 0-4, y 99 (made by map_spawn).
MAP = next(iter(MAPS))
SPAWN = (2.5, 100, 2.5)


def first_line(name, key):
    """The first line of a text in the server's server.toml, without its MiniMessage tags."""
    text = tomllib.loads((ROOT / f"servers/{name}/server.toml").read_text())[key]
    return re.sub(r"<[^>]+>", "", text.strip().splitlines()[0])


def pytest_addoption(parser):
    parser.addoption("--fresh", action="store_true", help="wipe the local stack and deploy the test inventory first")


@pytest.fixture(scope="session")
def stack(request):
    if request.config.getoption("--fresh"):
        harness.fresh_stack()
    harness.wait_for_servers()


@pytest.fixture(scope="session")
def player(stack):
    harness.build_client()
    player = harness.start_client()
    player.joined = [server.name for server in (HUB, FPV) if player.name in server.online()]
    # Players reach the other servers through the hub's portals; the test player switches directly.
    luckperms(f"user {player.name} clear")
    luckperms(f"user {player.name} clear", FPV)
    luckperms(f"user {player.name} permission set velocity.command.server")

    # The proxy learns about the permission a few seconds later; until then it passes /server on to
    # the hub, which doesn't know it.
    world = player.world()

    def allowed():
        player.clear_chat()
        player.command("/server fpv")
        try:
            player.expect_chat("Unknown or incomplete command", timeout=3)
            return False
        except AssertionError:
            return True
    eventually(allowed, timeout=60, interval=1, message="/server to work for the test player")
    player.expect_arrival(FPV, world)
    yield player
    harness.stop_client()


@pytest.fixture(autouse=True)
def reset(player):
    """Each test starts with an empty inventory, on whichever server the last one ended (see Player.go)."""
    for server in (HUB, FPV):
        if player.name in server.online():
            server.rcon(f"clear {player.name}")
    player.clear_chat()


@pytest.fixture
def builder(player):
    """The player in the builder group on fpv, for one test."""
    luckperms(f"user {player.name} parent add builder", FPV)
    yield player
    luckperms(f"user {player.name} parent remove builder", FPV)


@pytest.fixture(scope="session")
def map_spawn(player):
    """The map's spawn (its warp), set by a builder standing on the platform."""
    player.go(FPV)
    FPV.rcon(f"execute in maps:{MAP} run fill 0 99 0 4 99 4 minecraft:stone")
    FPV.rcon(f"execute in maps:{MAP} run fill 0 100 0 4 101 4 minecraft:air")
    luckperms(f"user {player.name} parent add builder", FPV)
    try:
        FPV.rcon(f"execute in maps:{MAP} run tp {player.name} {' '.join(map(str, SPAWN))}")
        player.expect_dimension(f"maps:{MAP}")
        player.command(f"/warp delete {MAP}")
        player.expect_chat(f"Warp '{MAP}' (has been|could not be) deleted")
        player.command(f"/warp set {MAP}")
        player.expect_chat(f"Warp '{MAP}' set")
    finally:
        luckperms(f"user {player.name} parent remove builder", FPV)
