#!/usr/bin/env bash
#
# Fails when suspend code under app/src/main catches everything without letting a cancellation
# through first.
#
# A CancellationException is how a coroutine stops: a worker Android stops, a timeout, a screen
# that goes away. `catch (e: Exception)`, `catch (e: Throwable)` and `runCatching` all catch it,
# and code that then carries on (logs a failure, bumps a streak, returns a default) turns a stop
# into a wrong result. 1.18.0 shipped exactly that. The rule in this code base: catch
# CancellationException first and rethrow it, then catch the rest.
#
# What counts as suspend code is decided per line, from the nearest function declared above it:
# a `suspend fun`, or a coroutine builder (launch, async, withContext, coroutineScope,
# withTimeout, runBlocking and friends) between that declaration and the catch. A catch is fine
# when one of the six lines above it catches CancellationException, or when its own line or the
# next three rethrow (`is CancellationException`, `ensureActive()`, or `throw e` of everything).
#
# That is a heuristic: it can flag a runCatching around code that cannot suspend. Those, and the
# real ones not fixed yet, are in ALLOWLIST as "file count". The count must match exactly: a new
# catch fails the check, and a fix fails it too until the count here is lowered, so the list only
# ever shrinks.
#
# Usage: scripts/check-cancellation.sh [--list]   (--list prints every hit)
#
set -euo pipefail

cd "$(dirname "$0")/.."

SRC="app/src/main/java"

# Known hits per file, as "path count". Every entry would be debt: lower the number when you
# fix one, and do not add to it. Empty since the F1 fixes of P2-4 (the per-type reads, the
# workers, the backoff delay of WebhookManager and the rest of the sync path).
ALLOWLIST="
"

LIST=0
[ "${1:-}" = "--list" ] && LIST=1

scan() {
    awk '
        function reset_context() { suspend_ctx = 0 }
        {
            lines[NR] = $0
        }
        /(^|[^A-Za-z_])fun[ \t]/ {
            suspend_ctx = ($0 ~ /suspend[ \t]+fun/) ? 1 : 0
        }
        /(^|[^A-Za-z_.])(launch|async|withContext|coroutineScope|supervisorScope|withTimeout|withTimeoutOrNull|runBlocking|flow|channelFlow)[ \t]*(\(|\{)/ {
            suspend_ctx = 1
        }
        {
            hit = ($0 ~ /catch[ \t]*\([ \t]*[A-Za-z_]+[ \t]*:[ \t]*(java\.lang\.|kotlin\.)?(Exception|Throwable)[ \t]*\)/) || ($0 ~ /runCatching[ \t]*(\{|\()/)
            if (hit && suspend_ctx) {
                ok = 0
                for (i = NR - 6; i < NR; i++) if (i > 0 && lines[i] ~ /catch[ \t]*\([^)]*CancellationException/) ok = 1
                if ($0 ~ /catch[ \t]*\([^)]*CancellationException/) ok = 1
                if (ok == 0) { pending[NR] = $0 }
            }
        }
        END {
            for (n in pending) {
                rethrows = 0
                m = n + 0; for (i = m; i <= m + 3 && i <= NR; i++) if (lines[i] ~ /is[ \t]+(kotlinx\.coroutines\.)?CancellationException|ensureActive\(\)|throw[ \t]+[A-Za-z_]+[ \t]*$/) rethrows = 1
                if (!rethrows) printf "%s:%d:%s\n", FILENAME, n, pending[n]
            }
        }
    ' "$1" | sort -t: -k2,2n
}

total=0
failed=0
report=""
while IFS= read -r file; do
    hits=$(scan "$file")
    count=0
    [ -n "$hits" ] && count=$(printf '%s\n' "$hits" | wc -l | tr -d ' ')
    allowed=$(printf '%s\n' "$ALLOWLIST" | awk -v f="$file" '$1 == f { print $2 }')
    allowed=${allowed:-0}
    total=$((total + count))
    if [ "$LIST" -eq 1 ] && [ "$count" -gt 0 ]; then printf '%s\n' "$hits"; fi
    if [ "$count" -ne "$allowed" ]; then
        failed=1
        if [ "$count" -gt "$allowed" ]; then
            report+="  $file: $count catch-alls in suspend code, $allowed allowed"$'\n'
            report+="$(printf '%s\n' "$hits" | sed 's/^/      /')"$'\n'
        else
            report+="  $file: $count left, the allowlist still says $allowed; lower it to $count"$'\n'
        fi
    fi
done < <(find "$SRC" -name "*.kt" | sort)

# An allowlist entry for a file that no longer exists is stale too.
while read -r path _; do
    [ -z "$path" ] && continue
    [ -f "$path" ] || { failed=1; report+="  $path is on the allowlist but does not exist"$'\n'; }
done <<< "$ALLOWLIST"

if [ "$failed" -ne 0 ]; then
    echo "Cancellation check failed:"
    echo ""
    printf '%s' "$report"
    echo ""
    echo "Catch CancellationException first and rethrow it:"
    echo "  } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ... }"
    echo "When a fix removes a hit, lower that file's number in ALLOWLIST in $0."
    exit 1
fi

echo "No new catch-alls in suspend code ($total known, on the allowlist)."
