#!/usr/bin/env bash
# Prints the CHANGELOG.md section for one version, without its heading, for a GitHub release.
#
#   .github/scripts/release-notes.sh 2.0.0 > notes.md
#
# A section starts at a "## " heading that has the version as one of its words, after [ and ] are
# taken out: "## [2.0.0] - 2026-01-01" and "## [Unreleased] - 2.0.0" both match. It ends at the next "## "
# heading. Exits 1, with a message on stderr, if there is no such heading or the section is empty.
set -euo pipefail
version=${1:?usage: release-notes.sh VERSION}
changelog=${CHANGELOG:-CHANGELOG.md}

awk -v v="$version" '
  function flush_blank() { while (pending > 0) { print ""; pending-- } }
  /^## / {
    if (found) exit
    heading = $0
    gsub(/[][]/, " ", heading)
    n = split(heading, words, /[ \t]+/)
    for (i = 1; i <= n; i++) if (words[i] == v) { found = 1; break }
    next
  }
  found {
    if ($0 ~ /^[ \t]*$/) { if (started) pending++; next }
    flush_blank()
    print
    started = 1
  }
  END { if (!found || !started) exit 1 }
' "$changelog" || {
  echo "release-notes: no non-empty CHANGELOG section for version $version in $changelog" >&2
  exit 1
}
