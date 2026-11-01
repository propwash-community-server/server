# As a new firework rocket: allowed if a player launched it by hand (not a crossbow) and their last
# allowed one was at least 2 seconds ago (a few ticks of slack, so it never removes one that
# tweaks' item cooldown on fpv and plots let through). Checked once, in the tick it appears.
tag @s add noprojectiles.checked
execute if entity @s[nbt={ShotAtAngle:1b}] run return fail
scoreboard players set #allowed noprojectiles.firework 0
execute on origin if entity @s[type=minecraft:player] unless score @s noprojectiles.firework matches 6.. run function noprojectiles:firework_start
execute if score #allowed noprojectiles.firework matches 1 run tag @s add noprojectiles.allowed
