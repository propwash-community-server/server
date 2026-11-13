# As the player who just placed a drone: their older drones in this dimension (map) are removed.
advancement revoke @s only propwash_drone_limit:placed_drone
execute unless score @s propwash.drone_owner matches 1.. store result score @s propwash.drone_owner run scoreboard players add #next propwash.drone_owner 1
scoreboard players operation #placer propwash.drone_owner = @s propwash.drone_owner

# The drone doesn't store who placed it, so the new one is the nearest drone not yet owned.
tag @e[type=propwash:drone,tag=!propwash.owned,distance=..8,sort=nearest,limit=1] add propwash.new
execute as @e[type=propwash:drone,tag=propwash.owned,distance=0..] if score @s propwash.drone_owner = #placer propwash.drone_owner run kill @s
scoreboard players operation @e[tag=propwash.new] propwash.drone_owner = #placer propwash.drone_owner
tag @e[tag=propwash.new] add propwash.owned
tag @e[tag=propwash.new] remove propwash.new
