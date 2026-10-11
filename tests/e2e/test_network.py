import pytest

import harness
from harness import FPV, HUB, Server, servers


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


@pytest.mark.parametrize("name", servers())
def test_switching_servers_says_nothing(player, name):
    player.go(Server(next(other for other in servers() if other != name)))
    player.clear_chat()
    player.go(Server(name))
    with pytest.raises(AssertionError):
        player.expect_chat(f"{player.name} (joined|left) the game", timeout=3)


def test_joining_and_leaving_the_network_is_announced(player):
    harness.start_client("Visitor", harness.AGENT_PORT + 2)
    try:
        player.expect_chat("Visitor joined (the game|for the first time)", timeout=30)
    finally:
        harness.stop_client("Visitor")
    player.expect_chat("Visitor left the game", timeout=30)


@pytest.mark.parametrize("name", servers())
def test_chat_reaches_the_player(player, name):
    player.go(Server(name))
    player.command(f"/g e2e chat on {name}")
    player.expect_chat(f"{player.name}: e2e chat on {name}$")
