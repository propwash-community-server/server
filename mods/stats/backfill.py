#!/usr/bin/env python3
"""Player activity from before mods/stats, rebuilt from the proxy's logs (run once, as root, on the VPS).

    python3 backfill.py            # what it would write, per month
    python3 backfill.py --write    # writes plugins/stats/backfill-YYYY-MM.jsonl

Same events as the plugin's events-YYYY-MM.jsonl (join, server, leave, start, stop; no online
samples), each with "src": "log". Only events before the plugin's first one, so nothing is counted
twice. Log times are the VPS's (UTC); the date comes from the log's name (one file per day).
UUIDs come from LuckPerms (every player who joined has a row); names it doesn't know are skipped.
"""
import collections
import glob
import gzip
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone

VELOCITY = "/home/mc/servers/velocity"
STATS = f"{VELOCITY}/plugins/stats"
TIME = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\] ")
JOIN = re.compile(r"\[connected player\] (\w{1,16}) \([^)]*\) has connected$")
LEAVE = re.compile(r"\[connected player\] (\w{1,16}) \([^)]*\) has disconnected")
SERVER = re.compile(r"\[server connection\] (\w{1,16}) -> (\w+) has connected$")


def uuids():
    out = subprocess.run(["mysql", "-N", "-e", "SELECT username, uuid FROM luckperms.luckperms_players"],
                         check=True, capture_output=True, text=True).stdout
    return dict(line.split("\t") for line in out.splitlines() if "\t" in line)


def first_live_event():
    times = []
    for path in glob.glob(f"{STATS}/events-*.jsonl"):
        with open(path, encoding="utf-8") as f:
            line = f.readline()
            if line.strip():
                times.append(json.loads(line)["t"])
    return min(times) if times else None


def log_files():
    # Rotated logs are <date>-<n>.log.gz; latest.log is today's.
    files = []
    for path in glob.glob(f"{VELOCITY}/logs/*.log.gz"):
        m = re.match(r"(\d{4}-\d\d-\d\d)-(\d+)\.log\.gz$", os.path.basename(path))
        if m:
            files.append((m[1], int(m[2]), path))
    files.sort()
    latest = f"{VELOCITY}/logs/latest.log"
    if os.path.exists(latest):
        day = datetime.fromtimestamp(os.path.getmtime(latest), timezone.utc).strftime("%Y-%m-%d")
        files.append((day, 10 ** 6, latest))
    return files


def events(names):
    skipped = collections.Counter()
    for day, _, path in log_files():
        date = datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=timezone.utc)
        last = None
        opener = gzip.open if path.endswith(".gz") else open
        with opener(path, "rt", encoding="utf-8", errors="replace") as f:
            for line in f:
                m = TIME.match(line)
                if not m:
                    continue
                t = date + timedelta(hours=int(m[1]), minutes=int(m[2]), seconds=int(m[3]))
                if last and t < last - timedelta(hours=1):
                    date += timedelta(days=1)  # past midnight in the same file
                    t += timedelta(days=1)
                last = t
                text = line.rstrip("\n")
                if "Booting up Velocity" in text:
                    yield t, {"e": "start"}
                elif "Shutting down the proxy" in text:
                    yield t, {"e": "stop"}
                elif m2 := (JOIN.search(text) or LEAVE.search(text) or SERVER.search(text)):
                    name = m2[1]
                    uuid = names.get(name.lower())
                    if not uuid:
                        skipped[name] += 1
                        continue
                    kind = "join" if m2.re is JOIN else "leave" if m2.re is LEAVE else "server"
                    o = {"e": kind, "uuid": uuid, "name": name}
                    if kind == "server":
                        o["server"] = m2[2]
                    yield t, o
    if skipped:
        print(f"skipped {sum(skipped.values())} lines of {len(skipped)} names LuckPerms doesn't know: "
              + ", ".join(sorted(skipped)[:10]), file=sys.stderr)


def main():
    write = "--write" in sys.argv[1:]
    cutoff = first_live_event()
    months = collections.defaultdict(list)
    current = {}  # uuid -> server, for "from" and the server on leave
    online = set()  # a kick logs two disconnect lines: one leave per join
    for t, o in events(uuids()):
        stamp = t.strftime("%Y-%m-%dT%H:%M:%SZ")
        if cutoff and stamp >= cutoff:
            break
        if o["e"] == "join":
            if o["uuid"] in online:
                continue
            online.add(o["uuid"])
        elif o["e"] == "leave":
            if o["uuid"] not in online:
                continue
            online.discard(o["uuid"])
        elif o["e"] in ("start", "stop"):
            online.clear()
        if o["e"] == "server":
            if o["uuid"] in current:
                o["from"] = current[o["uuid"]]
            current[o["uuid"]] = o["server"]
        elif o["e"] == "leave":
            if o["uuid"] in current:
                o["server"] = current.pop(o["uuid"])
        elif o["e"] in ("start", "stop"):
            current.clear()
        months[stamp[:7]].append({"t": stamp, **o, "src": "log"})
    for month, rows in sorted(months.items()):
        kinds = collections.Counter(r["e"] for r in rows)
        players = len({r["uuid"] for r in rows if "uuid" in r})
        print(f"{month}: {len(rows)} events, {players} players, {dict(kinds)}")
        if write:
            os.makedirs(STATS, exist_ok=True)
            path = f"{STATS}/backfill-{month}.jsonl"
            with open(path, "w", encoding="utf-8") as f:
                f.writelines(json.dumps(r, separators=(",", ":")) + "\n" for r in rows)
            os.system(f"chown mc:mc '{path}'")
            print(f"  wrote {path}")
    print("until", cutoff or "now (no live stats yet)")


if __name__ == "__main__":
    main()
