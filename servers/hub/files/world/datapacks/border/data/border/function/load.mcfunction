# The border around the hub area: centre -121 73, 600 blocks each way (size is the full width).
execute in maps:mountains run worldborder center -121 73
execute in maps:mountains run worldborder set 1200
# The Propwash Spawn map (the hub spawn since 0.6.0): centre 0 0 (written 0.0 so it is not moved to the block middle), 400 blocks each way.
execute in maps:spawn run worldborder center 0.0 0.0
execute in maps:spawn run worldborder set 800
