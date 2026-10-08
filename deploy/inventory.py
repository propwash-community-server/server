# Key auth as root; point ~/.ssh/config at your key if it isn't a default one.
prod = [("91.107.199.13", {"ssh_user": "root", "world_store": "https://fsn1.your-objectstorage.com/propwash-worlds"})]

# The containers from local/compose.yaml.
local = [("@docker/propwash-local", {"local": True, "world_store": "http://store:8080/worlds"})]
