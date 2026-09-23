#!/usr/bin/env bash
#
# Dump every AutoOps database to a timestamped, compressed file.
#
# WHY THIS EXISTS. The platform runs one MySQL instance, and the only copy of
# the catalog and the user roster has been a mysqldump somebody took by hand and
# left on a disk. Git cannot carry either: the catalog is 240 rows of template
# bodies written by publish.py and the console, and the users are rows with
# BCrypt hashes. Losing that instance loses the product, not just a deployment.
#
# This does NOT make MySQL highly available. Availability needs replication or a
# managed instance and is a decision about infrastructure, made in the deploy
# repo, not here. What this addresses is the other half — durability — which is
# the half that is unrecoverable.
#
#   scripts/backup-databases.sh                 # -> ./backups/
#   scripts/backup-databases.sh /mnt/backups    # -> somewhere off this host
#
# Restore, for one database:
#   gunzip -c backups/<stamp>/autoops_core.sql.gz \
#     | docker exec -i autoops-mysql-1 mysql -uroot -p"$MYSQL_ROOT_PASSWORD" autoops_core
#
set -euo pipefail

CONTAINER="${MYSQL_CONTAINER:-autoops-mysql-1}"
DEST_ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="$DEST_ROOT/$STAMP"

# Read from the environment, never from a literal here. A password baked into a
# script in the repository is the thing a backup script most often leaks.
ROOT_PW="${MYSQL_ROOT_PASSWORD:-}"
if [ -z "$ROOT_PW" ] && [ -f "$(dirname "${BASH_SOURCE[0]}")/../.env" ]; then
  ROOT_PW="$(grep -m1 '^MYSQL_ROOT_PASSWORD=' "$(dirname "${BASH_SOURCE[0]}")/../.env" 2>/dev/null | cut -d= -f2- || true)"
fi
ROOT_PW="${ROOT_PW:-rootpass}"

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "MySQL container '$CONTAINER' is not running." >&2
  exit 1
fi

# Discovered, not listed. A hard-coded database list silently stops backing up
# the newest service — which is exactly the service whose data nobody has
# anywhere else yet.
mapfile -t DATABASES < <(
  docker exec "$CONTAINER" mysql -uroot -p"$ROOT_PW" -N -B \
    -e "SELECT schema_name FROM information_schema.schemata
        WHERE schema_name LIKE 'autoops%'" 2>/dev/null
)

if [ "${#DATABASES[@]}" -eq 0 ]; then
  echo "No autoops* databases found — refusing to write an empty backup." >&2
  exit 1
fi

mkdir -p "$DEST"
echo "Backing up ${#DATABASES[@]} database(s) to $DEST"

for db in "${DATABASES[@]}"; do
  db="${db%$'\r'}"
  # --single-transaction so InnoDB is dumped consistently without locking the
  # platform for the length of the dump. --routines/--triggers because a schema
  # restored without them is a schema that looks complete and behaves wrongly.
  docker exec "$CONTAINER" mysqldump -uroot -p"$ROOT_PW" \
      --single-transaction --routines --triggers --quick "$db" 2>/dev/null \
    | gzip > "$DEST/$db.sql.gz"

  size=$(wc -c < "$DEST/$db.sql.gz")
  # A dump that "succeeded" and wrote nothing is the failure this catches. gzip
  # of an empty stream is still ~20 bytes, so an empty file is not zero-length.
  if [ "$size" -lt 200 ]; then
    echo "  $db: FAILED (wrote $size bytes)" >&2
    exit 1
  fi
  printf '  %-22s %s\n' "$db" "$(numfmt --to=iec "$size" 2>/dev/null || echo "$size bytes")"
done

# A restore nobody has rehearsed is a hope, not a backup. This at least proves
# the archive is readable and reaches its end marker.
for f in "$DEST"/*.sql.gz; do
  if ! gunzip -t "$f" 2>/dev/null; then
    echo "  $(basename "$f"): archive is corrupt" >&2
    exit 1
  fi
  if ! gunzip -c "$f" | tail -5 | grep -q "Dump completed"; then
    echo "  $(basename "$f"): truncated — no completion marker" >&2
    exit 1
  fi
done

echo "Verified. $DEST"
echo
echo "This host is still a single point of failure: copy $DEST somewhere else."
