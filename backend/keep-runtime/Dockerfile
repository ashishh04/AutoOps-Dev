# The alert plane, upstream and unmodified except for one thing it cannot do
# for itself.
#
# keep-api runs as the non-root user `keep` (uid 1000), and /state — where the
# SQLite database and the FILE secret manager live — does not exist in the
# published image. Docker therefore creates it root-owned and mode 0755 when it
# initialises the named volume, and Keep crash-loops on
#   sqlalchemy.exc.OperationalError: (sqlite3.OperationalError)
#   unable to open database file
# ...which restart:unless-stopped hides as a container stuck permanently in
# "health: starting", because every restart resets the healthcheck start_period.
#
# Creating the directory in the image with the right owner fixes it at the root:
# Docker seeds a fresh named volume from the image path, ownership included.
#
# Upstream's own compose sidesteps this with a bind mount (./state:/state) and
# a `mkdir state` in their quickstart. A named volume is the better fit here —
# it survives `docker compose down`, needs no host directory, and behaves the
# same on the Windows dev boxes and the Linux demo host.
FROM us-central1-docker.pkg.dev/keephq/keep/keep-api:0.54.3

USER root
RUN mkdir -p /state && chown -R keep:keep /state
USER keep
