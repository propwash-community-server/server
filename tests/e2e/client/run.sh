# Starts the client and joins the server given as $1. Minescript runs e2e_agent.py on join.
set -e
game=/root/.minecraft/fabric-26.3
mkdir -p "$game/minescript"
cp -r /hmc/mods "$game/"
cp /hmc/e2e_agent.py "$game/minescript/"
printf 'python="/usr/bin/python3"\nautorun[*] = e2e_agent\n' > "$game/minescript/config.txt"
printf 'pauseOnLostFocus:false\nonboardAccessibility:false\nnarrator:0\n' > "$game/options.txt"
exec java -jar headlessmc.jar launch --offline --headless --server "$1" fabric 26.3
