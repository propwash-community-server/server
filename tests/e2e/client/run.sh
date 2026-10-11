# Starts the client and joins the server given as $1 as the offline player $2. Minescript runs e2e_agent.py
# on join.
set -e
game=/root/.minecraft/fabric-26.3
mkdir -p "$game/minescript"
cp -r /hmc/mods "$game/"
cp /hmc/e2e_agent.py "$game/minescript/"
printf 'python="/usr/bin/python3"\nautorun[*] = e2e_agent\n' > "$game/minescript/config.txt"
printf 'pauseOnLostFocus:false\nonboardAccessibility:false\nnarrator:0\nrenderDistance:2\nsimulationDistance:5\n' > "$game/options.txt"
# The account that `headlessmc account -p offline login` would ask for.
auth=/root/.local/share/headlessmc/.auth
mkdir -p "$auth/offline" "$auth/last"
printf '{"accounts":{"%s":{"provider":"offline","name":"%s","uuid":"%s","token":"","type":"offline","xuid":""}},"version":0}' \
    "$2" "$2" "$(cat /proc/sys/kernel/random/uuid)" > "$auth/offline/.accounts.json"
printf '{"accounts":{"latest":[{"provider":"offline","name":"%s"}]},"version":0}' "$2" > "$auth/last/.accounts.json"
# Up to two clients run next to the servers on a CI runner: small heaps (the launcher stays running too) and
# few malloc arenas, which otherwise take hundreds of MB.
export MALLOC_ARENA_MAX=2
exec java -Xmx128M -jar headlessmc.jar launch --offline --headless --jvm "-Xms512M -Xmx1G" --server "$1" fabric 26.3
