import pytest

from conftest import MAP, MAPS, SPAWN
from harness import FPV, eventually


@pytest.fixture
def on_map(player, map_spawn):
    """The player on the map's platform."""
    player.go(FPV)
    FPV.rcon(f"execute in maps:{MAP} run tp {player.name} {' '.join(map(str, SPAWN))}")
    player.expect_dimension(f"maps:{MAP}")
    eventually(lambda: player.position() == list(SPAWN), message="the player on the platform")
    yield player
    FPV.rcon(f"execute in maps:{MAP} run kill @e[type=propwash:drone]")
    FPV.rcon(f"execute in maps:{MAP} run fill 0 100 0 4 101 4 minecraft:air")


def in_map(condition):
    return FPV.check(f"in maps:{MAP} {condition}")


def test_map_lists_every_map(player):
    player.go(FPV)
    player.command("/map")
    player.expect_chat(f"Maps: {', '.join(MAPS)}$")


def test_map_goes_to_the_spawn_and_shows_the_title(player, map_spawn):
    player.go(FPV)
    FPV.rcon(f"execute in minecraft:overworld run tp {player.name} 0 100 0")
    player.expect_dimension("minecraft:overworld")
    player.command(f"/map {MAP}")
    player.expect_dimension(f"maps:{MAP}")
    assert player.position() == list(SPAWN)
    player.expect_title(MAPS[MAP].get("title", MAP.capitalize()), f"by {MAPS[MAP]['author']}")
    player.expect_chat(f"^Teleported to {MAP}\\.$")


def test_map_on_the_map_shows_the_title_again(on_map):
    eventually(lambda: on_map.title()["title"] is None, timeout=15, message="the title to fade")
    on_map.command(f"/map {MAP}")
    on_map.expect_title(MAPS[MAP].get("title", MAP.capitalize()), f"by {MAPS[MAP]['author']}")


def test_blocks_cannot_be_broken(on_map):
    FPV.rcon(f"execute in maps:{MAP} run setblock 4 100 4 minecraft:stone")
    on_map.look_at(4.5, 100.5, 4.5)
    on_map.attack()
    assert in_map("if block 4 100 4 minecraft:stone")


def test_builders_can_break_blocks_in_bypass(builder, on_map):
    FPV.rcon(f"execute in maps:{MAP} run setblock 4 100 4 minecraft:stone")
    on_map.look_at(4.5, 100.5, 4.5)
    on_map.command("/flan bypass")
    try:
        def broken():
            on_map.attack()
            return in_map("if block 4 100 4 minecraft:air")
        eventually(broken, message="the block broken")
    finally:
        on_map.command("/flan bypass")


def test_blocks_cannot_be_placed(on_map):
    FPV.rcon(f"item replace entity {on_map.name} hotbar.0 with minecraft:stone")
    on_map.select_slot(0)
    on_map.look_at(0.5, 100, 4.5)
    on_map.use()
    assert in_map("if block 0 100 4 minecraft:air")


def test_drones_can_be_placed(on_map):
    FPV.rcon(f"item replace entity {on_map.name} hotbar.0 with propwash:drone")
    on_map.select_slot(0)
    on_map.look_at(0.5, 100, 4.5)
    on_map.use()
    eventually(lambda: in_map("if entity @e[type=propwash:drone]"), message="a drone on the map")


def drones(map, at=None):
    """The number of drones on the map, or only those within a block of `at` (x, y, z)."""
    near = f",x={at[0]},y={at[1]},z={at[2]},distance=..1" if at else ""
    out = FPV.rcon(f"execute in maps:{map} if entity @e[type=propwash:drone{near}]")
    return int(out.rsplit(" ", 1)[1]) if out.startswith("Test passed") else 0


def test_a_new_drone_replaces_the_players_drone_on_the_map(on_map):
    other = next(m for m in MAPS if m != MAP)
    FPV.rcon(f"item replace entity {on_map.name} hotbar.0 with propwash:drone")
    on_map.select_slot(0)
    on_map.look_at(0.5, 100, 4.5)
    on_map.use()
    eventually(lambda: drones(MAP) == 1, message="a drone on the map")

    tp = f"execute in maps:{other} run tp {on_map.name} {' '.join(map(str, SPAWN))}"
    FPV.rcon(tp)
    on_map.expect_dimension(f"maps:{other}")
    # The platform can only be made once the player has loaded the other map.
    eventually(lambda: "not loaded" not in FPV.rcon(f"execute in maps:{other} run fill 0 99 0 4 99 4 minecraft:stone"),
               message="the other map loaded")
    FPV.rcon(tp)
    eventually(lambda: on_map.position() == list(SPAWN), message="the player on the other map's platform")
    eventually(lambda: on_map.call("getblock", 0, 99, 4) == "minecraft:stone", message="the platform in the client")
    try:
        on_map.look_at(0.5, 100, 4.5)
        on_map.use()
        eventually(lambda: drones(other) == 1, message="a drone on the other map")
        assert drones(MAP) == 1

        FPV.rcon(f"execute in maps:{MAP} run tp {on_map.name} {' '.join(map(str, SPAWN))}")
        on_map.expect_dimension(f"maps:{MAP}")
        eventually(lambda: on_map.position() == list(SPAWN), message="the player back on the platform")
        on_map.look_at(4.5, 100, 4.5)
        on_map.use()
        eventually(lambda: drones(MAP, (4.5, 100, 4.5)) == 1, message="the new drone on the map")
        assert drones(MAP) == 1
        assert drones(other) == 1
    finally:
        FPV.rcon(f"execute in maps:{other} run kill @e[type=propwash:drone]")
