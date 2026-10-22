# Every tick: whoever falls below y -70 in the lobby (into the void under it) goes back to the spawn,
# a marker entity tagged propwash.lobby_spawn that staff place once, standing on the spot:
#   /summon minecraft:marker ~ ~ ~ {Tags:["propwash.lobby_spawn"]}
# It's part of the lobby map. Without a marker nothing happens.
execute as @a at @s if dimension maps:lobby if entity @s[y=-1000,dy=930] at @e[type=minecraft:marker,tag=propwash.lobby_spawn,limit=1] run tp @s ~ ~ ~
