# Runs on every server start, so these hold even after a world reset.

# New players and respawns land in the plot world (FabricPlots' own /plot world spot), not the overworld.
execute in fabricplots:plots run setworldspawn -9 69 -9
# Exactly at spawn, not scattered around it.
gamerule respawn_radius 0

# No mobs, fire spread, explosions or Nether.
gamerule spawn_mobs false
gamerule spawn_monsters false
gamerule spawn_patrols false
gamerule spawn_phantoms false
gamerule spawn_wandering_traders false
gamerule spawn_wardens false
gamerule spawner_blocks_work false
gamerule raids false
gamerule mob_griefing false
gamerule fire_spread_radius_around_player 0
gamerule tnt_explodes false
gamerule allow_entering_nether_using_portals false

# No "has made the advancement" in chat.
gamerule show_advancement_messages false
