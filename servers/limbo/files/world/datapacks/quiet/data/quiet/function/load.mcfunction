# Runs on every server start: no "has made the advancement" in chat.
gamerule show_advancement_messages false
# Paper keeps game rules per dimension: also the hub's spawn map (skipped where it doesn't exist).
execute in maps:mountains run gamerule show_advancement_messages false
