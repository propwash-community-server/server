execute as @a[tag=!hubspawned] in maps:spawn run tp @s 0.347 63 0.599 180 23.55
execute as @a[scores={hubspawn.left=1..}] in maps:spawn run tp @s 0.347 63 0.599 180 23.55
tag @a add hubspawned
scoreboard players reset @a[scores={hubspawn.left=1..}] hubspawn.left
