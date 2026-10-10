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
