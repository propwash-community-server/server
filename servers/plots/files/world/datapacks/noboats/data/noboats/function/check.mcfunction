# No boats anywhere: placed ones (and old ones, once their chunk loads) are removed, boat items cleared.
scoreboard players set #ticks noboats 0
kill @e[type=#noboats:boats]
clear @a #minecraft:boats
