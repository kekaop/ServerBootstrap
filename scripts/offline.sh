#!/bin/sh
set -eu
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
java_command=java
if [ -n "${JAVA_HOME:-}" ]; then java_command="$JAVA_HOME/bin/java"; fi
exec "$java_command" -cp "$base/lib/*" dev.kekaop.ServerBootstrap.OfflineMain "$@"
