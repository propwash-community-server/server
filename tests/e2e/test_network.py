from harness import FPV, HUB


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
