import re

import pytest

from conftest import first_line
from harness import Server, servers


@pytest.mark.parametrize("name", servers())
def test_arriving_shows_the_welcome(player, name):
    player.go(Server(next(other for other in servers() if other != name)))
    player.clear_chat()
    player.go(Server(name))
    player.expect_chat(re.escape(first_line(name, "welcome")))
