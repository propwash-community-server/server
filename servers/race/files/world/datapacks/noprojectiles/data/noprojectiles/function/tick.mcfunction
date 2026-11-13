# Every tick, so a shot or thrown projectile is gone before it flies or hits anything (the item is
# still used up). Not ones from players tagged propwash.exempt (admins: /tag @s add propwash.exempt).
# Fireworks: a player may launch one every 2 seconds (rocket boosting with an elytra too); others they
# launch within those 2 seconds, and ones from crossbows or dispensers, go like the rest.
scoreboard players remove @a[scores={noprojectiles.firework=1..}] noprojectiles.firework 1
execute as @e[type=minecraft:firework_rocket,tag=!noprojectiles.allowed,tag=!noprojectiles.checked] run function noprojectiles:firework
execute as @e[type=#noprojectiles:projectiles,tag=!noprojectiles.allowed] unless function noprojectiles:exempt run kill @s
