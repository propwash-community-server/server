import re

import pytest

from conftest import first_line
from harness import Server, servers


@pytest.mark.parametrize("name", servers())
def test_help_shows_the_server_and_network_help(player, name):
    player.go(Server(name))
    player.command("/help")
    player.expect_chat(re.escape(first_line(name, "help")))
    player.expect_chat(re.escape(first_line("velocity", "help")))
