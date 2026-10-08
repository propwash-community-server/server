# Stand-in for the VPS: same distro, systemd as PID 1 so mc@.service works.
FROM ubuntu:24.04
RUN apt-get update \
 && apt-get install -y --no-install-recommends systemd systemd-sysv python3 ca-certificates \
 && rm -rf /var/lib/apt/lists/*
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
