import pytest

import harness
from conftest import in_group
from harness import FPV, HUB, PLOTS, Server, eventually, luckperms, servers


def test_joining_lands_in_the_hub(player):
    assert player.joined == ["hub"]


def test_switching_servers(player):
    player.go(HUB)
    player.go(FPV)
    assert player.name not in HUB.online()
    player.go(HUB)
    assert player.name not in FPV.online()


def test_tab_list_shows_the_player(player):
    assert player.name in player.tab_players()


def test_tab_list_shows_the_server(player):
    player.go(HUB)
    eventually(lambda: f"[hub] {player.name}" in player.tab_names(), message="the server in the tab list")


@pytest.mark.parametrize("name", servers())
def test_switching_servers_says_nothing(player, name):
    player.go(Server(next(other for other in servers() if other != name)))
    player.clear_chat()
    player.go(Server(name))
    with pytest.raises(AssertionError):
        player.expect_chat(f"{player.name} (joined|left) the game", timeout=3)


def test_joining_and_leaving_the_network_is_announced(player):
    player.go(FPV)
    harness.start_client("Visitor", harness.AGENT_PORT + 2)
    try:
        player.expect_chat(r"Visitor joined (for the first time )?\(hub\)", timeout=30)
    finally:
        harness.stop_client("Visitor")
    player.expect_chat("Visitor left$", timeout=30)


def test_arriving_is_told_to_that_server_only(player, other):
    player.go(FPV)
    other.go(HUB)
    player.clear_chat()
    other.go(FPV)
    player.expect_chat("Other arrived from hub$")
    player.clear_chat()
    other.go(PLOTS)
    with pytest.raises(AssertionError):
        player.expect_chat("Other arrived", timeout=3)


def test_vanished_players_are_not_announced_or_listed(player, other):
    player.go(FPV)
    other.go(HUB)
    with in_group(other, "admin"):
        other.command("/v")
        other.expect_chat("You are vanished on every server")
        try:
            eventually(lambda: other.name not in player.tab_players(), message="Other gone from the tab list")
            player.clear_chat()
            other.go(FPV)
            with pytest.raises(AssertionError):
                player.expect_chat("Other arrived", timeout=3)
            assert other.name not in player.tab_players()
        finally:
            other.command("/v")
            other.expect_chat("You are visible again")
    eventually(lambda: other.name in player.tab_players(), message="Other back in the tab list")
