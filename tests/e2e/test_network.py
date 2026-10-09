from harness import FPV, HUB


def test_joining_lands_in_the_hub(player):
    assert player.name in HUB.online()


def test_switching_servers(player):
    player.command("/server fpv")
    player.expect_on(FPV)
    assert player.name not in HUB.online()
    player.command("/hub")
    player.expect_on(HUB)
    assert player.name not in FPV.online()


def test_tab_list_shows_the_player(player):
    assert player.name in player.tab_players()
