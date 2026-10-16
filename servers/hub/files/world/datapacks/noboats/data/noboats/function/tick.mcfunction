# Once a second (every 20 ticks).
scoreboard players add #ticks noboats 1
execute if score #ticks noboats matches 20.. run function noboats:check
