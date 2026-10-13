import harness
from conftest import in_group
from harness import CONTAINER, FPV, HUB, PLOTS, eventually, run

VISITOR = ("Visitor", harness.AGENT_PORT + 2)


def turned_away():
    """How often the proxy has turned Visitor away for maintenance, by its log."""
    log = run("docker", "exec", CONTAINER, "cat", "/home/mc/servers/velocity/logs/latest.log")
    return sum(1 for line in log.splitlines() if "Visitor" in line and "under maintenance" in line)


def test_maintenance_keeps_players_out_until_off(player, other):
    """/maintenance on kicks everyone but staff and keeps them out; /maintenance off lets them in again."""
    player.go(HUB)
    harness.start_client(*VISITOR)
    try:
        eventually(lambda: "Visitor" in HUB.online(), timeout=30, message="Visitor on the hub")
        with in_group(other, "staff"), in_group(player, "admin"):
            player.command("/maintenance on")
            try:
                player.expect_chat("Maintenance mode is now activated")
                eventually(lambda: "Visitor" not in HUB.online(), message="Visitor kicked")
                assert other.name in HUB.online() + FPV.online() + PLOTS.online()
                before = turned_away()
                harness.start_client(*VISITOR, wait=False)
                eventually(lambda: turned_away() > before, timeout=180, interval=2, message="Visitor turned away")
            finally:
                player.command("/maintenance off")
                player.expect_chat("Maintenance mode is now deactivated")
    finally:
        harness.stop_client("Visitor")
    harness.start_client(*VISITOR)
    try:
        eventually(lambda: "Visitor" in HUB.online(), timeout=30, message="Visitor on the hub")
    finally:
        harness.stop_client("Visitor")
