import pytest

from conftest import MAP
from harness import FPV, HUB

REFUSED = "Unknown or incomplete command|do not have access|Incorrect argument"


@pytest.mark.parametrize("server", [HUB, FPV], ids=lambda s: s.name)
def test_gamemode_is_refused(player, server):
    if server is not HUB:
        player.command(f"/server {server.name}")
        player.expect_on(server)
    before = server.rcon(f"data get entity {player.name} playerGameType")
    player.command("/gamemode spectator")
    player.expect_chat(REFUSED)
    assert server.rcon(f"data get entity {player.name} playerGameType") == before


@pytest.mark.parametrize("command", ["/warp set e2e", "/time set midnight"])
def test_builder_commands_are_refused_on_fpv(player, command):
    player.command("/server fpv")
    player.expect_on(FPV)
    player.command(command)
    player.expect_chat(REFUSED)


def test_builders_can_set_warps(builder):
    builder.command("/server fpv")
    builder.expect_on(FPV)
    builder.command("/warp set e2e_builder")
    builder.expect_chat("Warp 'e2e_builder' set")
    builder.command("/warp delete e2e_builder")
    builder.expect_chat("Warp 'e2e_builder' has been deleted")


def test_warps_and_hub_work(player, map_spawn):
    player.command("/server fpv")
    player.expect_on(FPV)
    FPV.rcon(f"execute in minecraft:overworld run tp {player.name} 0 100 0")
    player.expect_dimension("minecraft:overworld")
    player.command(f"/warp tp {MAP}")
    player.expect_dimension(f"maps:{MAP}")
    player.command("/hub")
    player.expect_on(HUB)
