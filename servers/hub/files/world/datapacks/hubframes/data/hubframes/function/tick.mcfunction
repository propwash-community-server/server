# Item frames that hold an item are made Fixed: nobody can rotate the item or take it out, and only
# creative players can break the frame. Empty frames stay free so admins can fill new ones.
# To edit frames, an admin runs /tag @s add hubframes.edit: frames within 8 blocks are unfixed while
# the tag is on (and fixed again once they leave or run /tag @s remove hubframes.edit).
execute as @a[tag=hubframes.edit] at @s as @e[type=#hubframes:item_frames,distance=..8] run data merge entity @s {Fixed:0b}
execute as @e[type=#hubframes:item_frames,nbt={Fixed:0b}] if data entity @s Item at @s unless entity @a[tag=hubframes.edit,distance=..8] run data merge entity @s {Fixed:1b}
