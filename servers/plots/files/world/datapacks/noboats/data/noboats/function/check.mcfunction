# No boats anywhere: placed ones (and old ones, once their chunk loads) are removed, boat items cleared.
# Except near and for players tagged propwash.exempt (admins: /tag @s add propwash.exempt).
scoreboard players set #ticks noboats 0
execute as @e[type=#noboats:boats] at @s unless entity @a[tag=propwash.exempt,distance=..16] run kill @s
clear @a[tag=!propwash.exempt] #minecraft:boats
