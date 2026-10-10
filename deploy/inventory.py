# Key auth as root; point ~/.ssh/config at your key if it isn't a default one.
prod = [("91.107.199.13", {"ssh_user": "root", "world_store": "https://fsn1.your-objectstorage.com/propwash-worlds"})]

# The containers from local/compose.yaml.
local = [("@docker/propwash-local", {"local": True, "world_store": "http://store:8080/worlds"})]

# The same containers for the end-to-end tests (tests/e2e): offline proxy so test clients
# can join without Mojang accounts, and little RAM so everything fits a CI runner.
test = [("@docker/propwash-local", {"local": True, "test": True, "world_store": "http://store:8080/worlds", "ram": "1G"})]
