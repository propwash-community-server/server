import tomllib

import pytest

import harness
from harness import FPV, HUB, ROOT, eventually, luckperms

MAPS = {p.stem: tomllib.loads(p.read_text()) for p in sorted(ROOT.glob("servers/fpv/maps/*.toml"))}
# The first map. The tests stand on a 5x5 stone platform at x/z 0-4, y 99 (made by map_spawn).
MAP = next(iter(MAPS))
SPAWN = (2.5, 100, 2.5)


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
    # Players reach the other servers through the hub's portals; the test player switches directly.
    luckperms(f"user {player.name} clear")
    luckperms(f"user {player.name} clear", FPV)
    luckperms(f"user {player.name} permission set velocity.command.server")

    def switched():
        player.command("/server fpv")
        try:
            return eventually(lambda: player.name in FPV.online(), timeout=3)
        except AssertionError:
            return False

    # The proxy learns about the permission a few seconds later; until then /server is unknown.
    eventually(switched, timeout=60, interval=1, message="/server to work for the test player")
    yield player
    harness.stop_client()


@pytest.fixture(autouse=True)
def reset(player):
    """Each test starts with the player in the hub, with an empty inventory."""
    if player.name not in HUB.online():
        player.command("/hub")
        player.expect_on(HUB)
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
    player.command("/server fpv")
    player.expect_on(FPV)
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
