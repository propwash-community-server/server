# Key auth as root; point ~/.ssh/config at your key if it isn't a default one.
prod = [("2.31.25.191", {"ssh_user": "root"})]

# The containers from local/compose.yaml.
local = [("@docker/propwash-local", {"local": True, "world_store": "http://store:8080/worlds"})]
