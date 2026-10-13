import re

import pytest

from conftest import STAFF_HELP, first_line, in_group
from harness import HUB, Server, servers


@pytest.mark.parametrize("name", servers())
def test_help_shows_the_server_and_network_help(player, name):
    player.go(Server(name))
    player.command("/help")
    player.expect_chat(re.escape(first_line(name, "help")))
    player.expect_chat(re.escape(first_line("velocity", "help")))


def test_help_has_no_staff_part_for_players(player):
    player.go(HUB)
    player.command("/help")
    player.expect_chat(re.escape(first_line("velocity", "help")))
    with pytest.raises(AssertionError):
        player.expect_chat(STAFF_HELP, timeout=3)


@pytest.mark.parametrize("group", ["staff", "admin"])
def test_help_has_a_staff_part_for_staff(player, group):
    player.go(HUB)
    with in_group(player, group):
        player.clear_chat()
        player.command("/help")
        player.expect_chat(re.escape(first_line("hub", "help")))
        player.expect_chat(STAFF_HELP)
