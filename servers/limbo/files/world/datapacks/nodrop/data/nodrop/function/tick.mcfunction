# Every tick, so a dropped item is gone before it lands (fpv and plots do this with mods/tweaks).
# Not ones dropped by players tagged propwash.exempt (admins: /tag @s add propwash.exempt).
execute as @e[type=minecraft:item] unless function nodrop:exempt run kill @s
