import re
import tomllib

import pytest

from harness import ROOT, Server, servers


def first_line(name):
    """The first line of the server's help, without its MiniMessage tags."""
    help = tomllib.loads((ROOT / f"servers/{name}/server.toml").read_text())["help"]
    return re.sub(r"<[^>]+>", "", help.strip().splitlines()[0])


@pytest.mark.parametrize("name", servers())
def test_help_shows_the_server_and_network_help(player, name):
    player.go(Server(name))
    player.command("/help")
    player.expect_chat(re.escape(first_line(name)))
    player.expect_chat(re.escape(first_line("velocity")))
