#!/usr/bin/env bash
# Fails the build if the agent jar would pollute a target application's namespace.
#
# The agent is loaded with -javaagent, which puts it on the SYSTEM classloader: every class it
# ships is visible to the application being monitored. An unrelocated dependency class here
# silently shadows the app's own copy and can break it in ways that look nothing like a
# monitoring problem. This is the single most important safety property of the artifact.
set -euo pipefail

JAR="${1:-agent/target/jfrom-agent.jar}"
[[ -f "$JAR" ]] || { echo "verify-shading: no jar at $JAR" >&2; exit 1; }

entries=$(unzip -Z1 "$JAR")

# Every shipped class must live under io/jfrom/ (ours, or relocated deps).
leaked=$(grep '\.class$' <<<"$entries" | grep -v '^io/jfrom/' || true)
if [[ -n "$leaked" ]]; then
    echo "verify-shading: FAIL - classes outside io/jfrom/:" >&2
    sed 's/^/  /' <<<"$leaked" >&2
    exit 1
fi

# The relocation must actually have happened, rather than the dependency vanishing.
count=$(grep -c '^io/jfrom/agent/shaded/snakeyaml/.*\.class$' <<<"$entries" || true)
if (( count < 50 )); then
    echo "verify-shading: FAIL - only $count relocated snakeyaml classes; relocation likely broke" >&2
    exit 1
fi

echo "verify-shading: OK - $(grep -c '\.class$' <<<"$entries") classes, all under io/jfrom/ ($count relocated)"
