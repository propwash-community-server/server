# Every tick, so a shot or thrown projectile is gone before it flies or hits anything (the item is
# still used up). Fireworks too, so no rocket boosting. Not ones from players tagged propwash.exempt
# (admins: /tag @s add propwash.exempt).
execute as @e[type=#noprojectiles:projectiles] unless function noprojectiles:exempt run kill @s
