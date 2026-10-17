# Runs on every server start, so this holds even after a world reset.

# New players and respawns land in the plot world (FabricPlots' own /plot world spot), not the overworld.
execute in fabricplots:plots run setworldspawn -9 69 -9

# Propwash also saves assisted laps (its save_assisted_laps rule), on every server start.
gamerule propwash:save_assisted_laps true
