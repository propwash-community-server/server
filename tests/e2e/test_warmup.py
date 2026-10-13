import harness
from conftest import in_group
from harness import CONTAINER, FPV, HUB, PLOTS, eventually, run

LOG = "/home/mc/servers/velocity/logs/latest.log"
VISITOR = ("Visitor", harness.AGENT_PORT + 2)


def turned_away():
    """How often the proxy has told Visitor it is closed (mods/warmup), by its log."""
    log = run("docker", "exec", CONTAINER, "cat", LOG)
    return sum(1 for line in log.splitlines() if "Visitor" in line and "temporarily closed" in line)


def test_lockdown_keeps_players_out_until_open(player, other):
    """/lockdown kicks everyone but staff and keeps them out; /open lets them in again."""
    player.go(HUB)
    harness.start_client(*VISITOR)
    eventually(lambda: "Visitor" in HUB.online(), timeout=30, message="Visitor on the hub")
    with in_group(other, "staff"), in_group(player, "admin"):
        try:
            player.command("/lockdown")
            player.expect_chat("Lockdown: kicked 1 player")
            eventually(lambda: "Visitor" not in HUB.online(), message="Visitor kicked")
            before = turned_away()
            assert other.name in HUB.online() + FPV.online() + PLOTS.online()
            harness.start_client(*VISITOR, wait=False)
            eventually(lambda: turned_away() > before, timeout=180, interval=2, message="Visitor turned away")
        finally:
            harness.stop_client("Visitor")
            player.command("/open")
            player.expect_chat("Lockdown over")
    harness.start_client(*VISITOR)
    try:
        eventually(lambda: "Visitor" in HUB.online(), timeout=30, message="Visitor on the hub")
    finally:
        harness.stop_client("Visitor")
