# Every tick: nobody stays in the overworld (an unused flat world every server must have; players
# who ended up there in survival fell to their death). They go to the plot world spawn, as in load.
execute as @a at @s if dimension minecraft:overworld in fabricplots:plots run tp @s 40 75 39
# New players spawn straight into the plot world, so FabricPlots (manage-gamemode: creative on entering it)
# never switches them and they'd stay in the server's survival default.
execute as @a[gamemode=survival] at @s if dimension fabricplots:plots run gamemode creative @s
