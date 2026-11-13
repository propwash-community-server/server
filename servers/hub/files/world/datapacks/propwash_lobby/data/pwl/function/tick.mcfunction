scoreboard players add #t pwl.t 1
execute if score #t pwl.t matches 400.. run scoreboard players set #t pwl.t 0
scoreboard players operation #m2 pwl.t = #t pwl.t
scoreboard players operation #m2 pwl.t %= #2 pwl.t
scoreboard players operation #m4 pwl.t = #t pwl.t
scoreboard players operation #m4 pwl.t %= #4 pwl.t
execute as @e[type=minecraft:marker,tag=pwl.fx] at @s run function pwl:fx/any
