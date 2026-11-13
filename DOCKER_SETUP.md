# Docker

See the [README](README.md#quick-start) and [docker-compose.yml](docker-compose.yml). The image listens on
port 1985 inside its container and publishes nothing. Publish it to `127.0.0.1` (`-p 127.0.0.1:1985:1985`)
unless other machines need to reach it, and keep the data on a volume mounted at `/data`.
