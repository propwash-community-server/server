#!/usr/bin/env python3
"""A quick look at the player stats (mods/stats), run on the VPS:

    python3 report.py              # every day so far
    python3 report.py --days 7     # the last 7 days

Reads plugins/stats/backfill-*.jsonl (from the logs) and events-*.jsonl (the plugin), UTC days.
A session missing its leave (stats 1.0.0 sometimes didn't write one) ends at the player's last event:
at their next join, or when the 5-minute online count says fewer are online.
First a sanity check of the data, then per day: players, new players (first time ever seen),
sessions, play time, peak online and time per server; then the top players by play time.
"""
import collections
import glob
import json
import sys
from datetime import datetime, timedelta, timezone

STATS = "/home/mc/servers/velocity/plugins/stats"


def parse(t):
    return datetime.fromisoformat(t.replace("Z", "+00:00"))


def load():
    rows = []
    for path in sorted(glob.glob(f"{STATS}/backfill-*.jsonl")) + sorted(glob.glob(f"{STATS}/events-*.jsonl")):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    o = json.loads(line)
                    o["_t"] = parse(o["t"])
                    rows.append(o)
    rows.sort(key=lambda o: o["_t"])
    return rows


def split(start, end):
    """(day, seconds) pieces of a span, cut at UTC midnights."""
    while start < end:
        midnight = (start + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
        piece = min(end, midnight)
        yield start.date(), (piece - start).total_seconds()
        start = piece


def main():
    days_wanted = int(sys.argv[sys.argv.index("--days") + 1]) if "--days" in sys.argv else None
    rows = load()
    if not rows:
        print("No stats yet.")
        return
    now = datetime.now(timezone.utc)

    problems = collections.Counter()
    online = {}  # uuid -> (joined at, server, since)
    seen = {}  # uuid -> their last event in the open session (a session missing its leave ends there)
    names = {}
    first_seen = {}
    days = collections.defaultdict(lambda: {"players": set(), "new": set(), "sessions": 0, "seconds": 0.0,
                                            "servers": collections.Counter(), "peak": 0, "sampled": 0.0})
    playtime = collections.Counter()
    last_sample = None
    sample_gaps = 0

    def end_session(uuid, at):
        joined, server, since = online.pop(uuid)
        for day, s in split(joined, at):
            days[day]["seconds"] += s
        playtime[uuid] += (at - joined).total_seconds()
        if server:
            for day, s in split(since, at):
                days[day]["servers"][server] += s

    for o in rows:
        t, e = o["_t"], o["e"]
        if e in ("start", "stop"):
            for uuid in list(online):
                # A stop ends every session; a start without a stop (a crash) at each one's last event.
                end_session(uuid, t if e == "stop" else seen[uuid])
            last_sample = None
            continue
        if e == "online":
            day = t.date()
            days[day]["peak"] = max(days[day]["peak"], o["total"])
            days[day]["sampled"] += o["total"] * 5 / 60  # each sample stands for 5 minutes
            if last_sample and (t - last_sample) > timedelta(minutes=6):
                sample_gaps += 1
            last_sample = t
            # Fewer online than open sessions: some leaves are missing (stats 1.0.0). Those whose last
            # event is the oldest are the ones gone, since then.
            if o["total"] < len(online):
                for uuid in sorted(online, key=lambda u: seen[u])[:len(online) - o["total"]]:
                    end_session(uuid, seen[uuid])
                    problems["sessions closed by the online count (leave missing)"] += 1
            continue
        uuid = o.get("uuid")
        names[uuid] = o.get("name", uuid)
        if e == "join":
            if uuid in online:
                problems["join while already online (leave missing)"] += 1
                end_session(uuid, seen[uuid])
            online[uuid] = (t, None, t)
            seen[uuid] = t
            day = t.date()
            days[day]["players"].add(uuid)
            days[day]["sessions"] += 1
            if uuid not in first_seen:
                first_seen[uuid] = t
                days[day]["new"].add(uuid)
        elif e == "server":
            if uuid not in online:
                problems["server switch without a join"] += 1
                continue
            joined, server, since = online[uuid]
            if server:
                for day, s in split(since, t):
                    days[day]["servers"][server] += s
            online[uuid] = (joined, o["server"], t)
            seen[uuid] = t
        elif e == "leave":
            if uuid not in online:
                problems["leave without a join"] += 1
                continue
            end_session(uuid, t)
    still = len(online)
    for uuid in list(online):
        end_session(uuid, now)

    print(f"Data: {len(rows)} events, {rows[0]['_t']:%Y-%m-%d %H:%M} to {rows[-1]['_t']:%Y-%m-%d %H:%M} UTC, "
          f"{len(first_seen)} players ever")
    print(f"Online now (by the data): {still}")
    print("Checks: " + (", ".join(f"{k}: {v}" for k, v in problems.items()) if problems else "no problems")
          + f"; online samples more than 6 min apart: {sample_gaps}")
    print()
    print("hours: from sessions; sampled: from the 5-minute online counts (only since the plugin, more exact).")
    print(f"{'day':10}  {'players':>7}  {'new':>4}  {'sessions':>8}  {'hours':>6}  {'sampled':>7}  {'peak':>4}  per server (hours)")
    shown = sorted(days)[-days_wanted:] if days_wanted else sorted(days)
    for day in shown:
        d = days[day]
        per = ", ".join(f"{s} {sec / 3600:.1f}" for s, sec in d["servers"].most_common())
        sampled = f"{d['sampled']:.1f}" if d["sampled"] else "-"
        print(f"{day}  {len(d['players']):>7}  {len(d['new']):>4}  {d['sessions']:>8}  {d['seconds'] / 3600:>6.1f}  "
              f"{sampled:>7}  {d['peak'] or '-':>4}  {per}")
    print()
    print("Top players by play time:")
    for uuid, sec in playtime.most_common(10):
        print(f"  {names.get(uuid, uuid):16} {sec / 3600:6.1f} h  (first seen {first_seen[uuid]:%Y-%m-%d})")


if __name__ == "__main__":
    main()
