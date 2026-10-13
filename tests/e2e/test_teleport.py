import math

from conftest import in_group
from harness import FPV, PLOTS, eventually


def near(server, a, b):
    """Whether two players on the server stand within two blocks of each other."""
    def pos(name):
        return [float(v.rstrip("d")) for v in server.rcon(f"data get entity {name} Pos").split("[", 1)[1].rstrip("]").split(", ")]
    return math.dist(pos(a), pos(b)) < 2


def test_tpa_across_servers(player, other):
    player.go(FPV)
    other.go(PLOTS)
    other.clear_chat()
    player.command(f"/tpa {other.name}")
    player.expect_chat(f"Request sent to {other.name}")
    other.expect_chat(f"{player.name} wants to teleport to you")
    other.command("/tpaccept")
    player.expect_chat(f"{other.name} accepted your request")
    eventually(lambda: player.name in PLOTS.online(), timeout=30, message=f"{player.name} on plots")
    eventually(lambda: near(PLOTS, player.name, other.name), message="the players next to each other")


def test_tpahere_can_be_denied(player, other):
    player.go(FPV)
    other.go(PLOTS)
    other.clear_chat()
    player.command(f"/tpahere {other.name}")
    other.expect_chat(f"{player.name} wants you to teleport to them")
    other.command("/tpdeny")
    player.expect_chat(f"{other.name} denied your request")
    assert other.name in PLOTS.online()


def test_staff_tp_across_servers(player, other):
    player.go(FPV)
    other.go(PLOTS)
    with in_group(player, "staff"):
        player.command(f"/tp {other.name}")
        eventually(lambda: player.name in PLOTS.online(), timeout=30, message=f"{player.name} on plots")
        eventually(lambda: near(PLOTS, player.name, other.name), message="the players next to each other")


def test_staff_invsee_across_servers(player, other):
    player.go(FPV)
    other.go(PLOTS)
    with in_group(player, "staff"):
        player.command(f"/invsee {other.name}")
        eventually(lambda: player.name in PLOTS.online(), timeout=30, message=f"{player.name} on plots")
        eventually(lambda: player.call("world_state")["screen"], message="the inventory open")
        player.go(FPV)
