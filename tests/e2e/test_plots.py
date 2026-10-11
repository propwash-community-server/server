import time
import tomllib

import pytest

from harness import PLOTS, ROOT, eventually

CONFIG = tomllib.loads((ROOT / "servers/plots/server.toml").read_text())["config"]["config/fabricplots.properties"]
# Plot n starts at x/z n * STEP: a plot and the 17 wide road after it.
SIZE = int(CONFIG["plot-size"])
STEP = SIZE + 17
# The top of the ground on plots; roads are a block lower.
GROUND = 66
# The middle of a road, far outside the spawn area (street-sweeper-spawn-radius around the spawn).
ROAD = (3 * STEP + SIZE + 8, SIZE // 2)


def in_plots(command):
    PLOTS.rcon(f"execute in fabricplots:plots run {command}")


def check(condition):
    return PLOTS.check(f"in fabricplots:plots {condition}")


def still(condition):
    """For what must not happen: the condition still holds a moment later."""
    time.sleep(1)
    return check(condition)


def tp(player, x, y, z):
    in_plots(f"tp {player.name} {x} {y} {z}")
    eventually(lambda: player.position() == [x, y, z], message=f"{player.name} at {x} {y} {z}")


def place(player, x, y, z):
    """Places stone on top of the block at x y z."""
    PLOTS.rcon(f"item replace entity {player.name} hotbar.0 with minecraft:stone")
    player.select_slot(0)
    player.look_at(x + 0.5, y + 1, z + 0.5)
    player.use()


def hit(player, x, y, z):
    player.look_at(x + 0.5, y + 0.5, z + 0.5)
    player.attack()


def use(player, x, y, z):
    player.look_at(x + 0.5, y + 0.2, z + 0.5)
    player.use()


@pytest.fixture(scope="module")
def plot(player):
    """The x/z of the middle of a plot the player claims with /plot auto, and gives up afterwards."""
    player.go(PLOTS)
    player.command("/plot auto")
    match = player.expect_chat(r"Claimed plot (-?\d+),(-?\d+)")
    x, z = int(match[1]) * STEP + SIZE // 2, int(match[2]) * STEP + SIZE // 2
    yield x, z
    player.go(PLOTS)
    tp(player, x + 0.5, GROUND + 1, z + 0.5)
    # /plot delete asks to be run again.
    player.command("/plot delete")
    player.expect_chat("Run /plot delete again")
    player.command("/plot delete")
    player.expect_chat("Released plot")


@pytest.fixture
def on_plot(plot, player, other):
    """The owner in the middle of the plot and the other player next to them, both with nothing in hand.
    Tests build at x+2 z, in reach of both."""
    x, z = plot
    player.go(PLOTS)
    other.go(PLOTS)
    PLOTS.rcon(f"clear {other.name}")
    in_plots(f"fill {x - 3} {GROUND + 1} {z - 3} {x + 3} {GROUND + 4} {z + 3} minecraft:air")
    tp(player, x + 0.5, GROUND + 1, z + 0.5)
    tp(other, x + 0.5, GROUND + 1, z - 2.5)
    yield x, z
    in_plots(f"fill {x - 3} {GROUND + 1} {z - 3} {x + 3} {GROUND + 4} {z + 3} minecraft:air")


def test_owner_builds_and_breaks(on_plot, player):
    x, z = on_plot
    in_plots(f"setblock {x + 2} {GROUND + 1} {z} minecraft:stone")

    def placed():
        place(player, x + 2, GROUND + 1, z)
        return check(f"if block {x + 2} {GROUND + 2} {z} minecraft:stone")
    eventually(placed, message="the block placed")

    def broken():
        hit(player, x + 2, GROUND + 2, z)
        return check(f"if block {x + 2} {GROUND + 2} {z} minecraft:air")
    eventually(broken, message="the block broken")


def test_others_cannot_break(on_plot, other):
    x, z = on_plot
    in_plots(f"setblock {x + 2} {GROUND + 1} {z} minecraft:stone")
    hit(other, x + 2, GROUND + 1, z)
    assert still(f"if block {x + 2} {GROUND + 1} {z} minecraft:stone")


def test_others_cannot_place(on_plot, other):
    x, z = on_plot
    in_plots(f"setblock {x + 2} {GROUND + 1} {z} minecraft:stone")
    place(other, x + 2, GROUND + 1, z)
    assert still(f"if block {x + 2} {GROUND + 2} {z} minecraft:air")


def test_others_cannot_flip_levers(on_plot, other):
    x, z = on_plot
    in_plots(f"setblock {x + 2} {GROUND + 1} {z} minecraft:lever[face=floor]")
    use(other, x + 2, GROUND + 1, z)
    assert still(f"if block {x + 2} {GROUND + 1} {z} minecraft:lever[powered=false]")


def test_others_cannot_open_containers(on_plot, other):
    x, z = on_plot
    in_plots(f"setblock {x + 2} {GROUND + 1} {z} minecraft:barrel[facing=up]")
    use(other, x + 2, GROUND + 1, z)
    assert still(f"if block {x + 2} {GROUND + 1} {z} minecraft:barrel[open=false]")
    assert other.call("world_state")["screen"] is None


def test_trusted_players_build_until_untrusted(on_plot, player, other):
    x, z = on_plot
    in_plots(f"setblock {x + 2} {GROUND + 1} {z} minecraft:stone")
    player.command(f"/plot trust {other.name}")
    player.expect_chat(f"Trusted {other.name}")
    try:
        def placed():
            place(other, x + 2, GROUND + 1, z)
            return check(f"if block {x + 2} {GROUND + 2} {z} minecraft:stone")
        eventually(placed, message="the block placed")

        def broken():
            hit(other, x + 2, GROUND + 2, z)
            return check(f"if block {x + 2} {GROUND + 2} {z} minecraft:air")
        eventually(broken, message="the block broken")
    finally:
        player.command(f"/plot untrust {other.name}")
        player.expect_chat(f"Untrusted {other.name}")

    hit(other, x + 2, GROUND + 1, z)
    assert still(f"if block {x + 2} {GROUND + 1} {z} minecraft:stone")
    place(other, x + 2, GROUND + 1, z)
    assert still(f"if block {x + 2} {GROUND + 2} {z} minecraft:air")


def test_roads_cannot_be_built_on(player):
    x, z = ROAD
    player.go(PLOTS)
    tp(player, x + 0.5, GROUND, z + 0.5)
    assert check(f"unless block {x} {GROUND - 1} {z + 2} minecraft:air")
    hit(player, x, GROUND - 1, z + 2)
    assert still(f"unless block {x} {GROUND - 1} {z + 2} minecraft:air")
    place(player, x, GROUND - 1, z + 2)
    assert still(f"if block {x} {GROUND} {z + 2} minecraft:air")
