execute as @a[tag=!hubspawned] in maps:mountains run tp @s -121.47 -31 73.51 -134.5 0.6
execute as @a[scores={hubspawn.left=1..}] in maps:mountains run tp @s -121.47 -31 73.51 -134.5 0.6
tag @a add hubspawned
scoreboard players reset @a[scores={hubspawn.left=1..}] hubspawn.left
