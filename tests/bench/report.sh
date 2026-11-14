#!/usr/bin/env bash
# Markdown from the results of tests/bench/run.sh: the report of one run, or of several runs side by side.
#
#   tests/bench/report.sh DIR                          one run (run.sh writes this to DIR/results.md)
#   tests/bench/report.sh NAME=DIR NAME=DIR ...        one table per scenario, a column per run for each figure
#   UPDATE_DOC=1 tests/bench/report.sh NAME=DIR ...    also put the output between the bench markers in
#                                                      docs/BENCHMARKS.md (refused for QUICK=1 runs)
#
# DIR is the OUT directory of a run (results.tsv and meta.tsv). NAME labels the run's columns; the
# default is the version the image reports. Needs bash, awk and sed only.
set -euo pipefail
cd "$(dirname "$0")/../.."
DOC=docs/BENCHMARKS.md

[ $# -ge 1 ] || { sed -n '2,10p' "$0"; exit 2; }
m() { awk -F'\t' -v k="$2" '$1 == k { sub(/^[^\t]*\t/, ""); v = $0 } END { print v }' "$1/meta.tsv"; }
NAMES=() DIRS=() TSVS=()
for a in "$@"; do
  case $a in *=*) n=${a%%=*} d=${a#*=} ;; *) n="" d=$a ;; esac
  [ -f "$d/results.tsv" ] && [ -f "$d/meta.tsv" ] || { echo "report: $d has no results.tsv and meta.tsv" >&2; exit 1; }
  NAMES+=("${n:-$(m "$d" version)}"); DIRS+=("$d"); TSVS+=("$d/results.tsv")
done
N=${#DIRS[@]}
NAMESTR=$(IFS='|'; printf '%s' "${NAMES[*]}")
first=${DIRS[0]}

if [ "${UPDATE_DOC:-0}" = 1 ]; then
  for d in "${DIRS[@]}"; do
    if [ "$(m "$d" quick)" = 1 ]; then echo "report: $d is a QUICK=1 run; its numbers are not for the docs" >&2; exit 2; fi
  done
fi

# fact KEY: the value from meta.tsv, once if every run has the same, else "NAME: value; NAME: value"
fact() {
  local i v diff=0 out="" f
  f=$(m "$first" "$1")
  for i in "${!DIRS[@]}"; do v=$(m "${DIRS[$i]}" "$1"); [ "$v" = "$f" ] || diff=1; done
  if [ "$diff" = 0 ]; then printf '%s' "$f"; return; fi
  for i in "${!DIRS[@]}"; do out="$out${out:+; }${NAMES[$i]}: $(m "${DIRS[$i]}" "$1")"; done
  printf '%s' "$out"
}
# per_run TEXT-COMMAND: "NAME: output; NAME: output" (just the output for one run); the command gets DIR and TSV
per_run() {
  local i out=""
  for i in "${!DIRS[@]}"; do
    if [ "$N" = 1 ]; then "$@" "${DIRS[$i]}" "${TSVS[$i]}"; return; fi
    out="$out${out:+; }${NAMES[$i]}: $("$@" "${DIRS[$i]}" "${TSVS[$i]}")"
  done
  printf '%s' "$out"
}
dd_of() { awk -F'\t' '$1 == "D" { printf "%s ms", $3 }' "$2"; }
load_of() {
  awk -F'\t' -v cpu="$(m "$1" cpu_who)" -v oc="$(m "$1" other_containers)" -v ls="$(m "$1" load_start)" -v le="$(m "$1" load_end)" '
    $1 == "Q" { q = $2; qw = $3 }
    $1 == "A" { if ($3 + 0 > amax + 0) amax = $3 + 0 }
    END { printf "%s other containers at the start; %s %s%% busy before the first scenario (after waiting %s s) and at most %s%% before any scenario, with no benchmark node running; load averages %s at the start, %s at the end",
      oc, cpu, q, qw, amax + 0, ls, le }' "$2"
}

# Shared awk functions. spread: the median of a space-separated list, with the lowest and highest value
# when there is more than one.
LIB='
function sortn(a, n,   i, j, v) { for (i = 2; i <= n; i++) { v = a[i]; j = i - 1; while (j >= 1 && a[j] + 0 > v + 0) { a[j + 1] = a[j]; j-- } a[j + 1] = v } }
function num(x) { x += 0; return (x == 0 || x >= 100) ? sprintf("%.0f", x) : (x >= 10) ? sprintf("%.1f", x) : sprintf("%.2f", x) }
function median(s,   a, n) { n = split(s, a, " "); if (n == 0) return ""; sortn(a, n); return (n % 2) ? a[(n + 1) / 2] : (a[n / 2] + a[n / 2 + 1]) / 2 }
function spread(s,   a, n) { n = split(s, a, " "); if (n == 0) return "-"; sortn(a, n); return num(median(s)) (n > 1 ? " (" num(a[1]) "-" num(a[n]) ")" : "") }
function head(title,   i) { for (i = 1; i <= N; i++) { H = H " " title (N > 1 ? ", " nm[i] : "") " |"; S = S "---:|" } }
function names() { N = split(namestr, nm, "|") }
'

# latency_table SCENARIO KEYHEADER [CONFIGHEADER] [fsync] [converge]: one row per (config, key) and a
# column per run for each figure. Requests/s is the median over the runs with their range; the
# latencies and the fsync figures come from the run with the median rate.
latency_table() {
  local sc=$1 keyname=$2 cfgname=${3:-} fsync=0 conv=0
  case " ${*:4} " in *" fsync "*) fsync=1 ;; esac
  case " ${*:4} " in *" converge "*) conv=1 ;; esac
  awk -F'\t' -v sc="$sc" -v namestr="$NAMESTR" -v keyname="$keyname" -v cfgname="$cfgname" -v fsync="$fsync" \
    -v conv="$conv" "$LIB"'
    FNR == 1 { fi++ }
    $1 == "S" && $2 == sc { skip[fi] = $3 }
    $1 == "L" && $2 == sc {
      ck = $3 SUBSEP $4
      if (!(ck in seen)) { seen[ck] = 1; order[++nk] = ck }
      j = ++n[fi, ck]
      rate[fi, ck, j] = $8; p50[fi, ck, j] = $9; p99[fi, ck, j] = $11; ok[fi, ck, j] = $6
      forces[fi, ck, j] = $13; fsecs[fi, ck, j] = $14
      rates[fi, ck] = rates[fi, ck] " " $8
      err[fi, ck] += $7; tot[fi, ck] += $6 + $7
      if ($16 != "") det[fi, ck] = det[fi, ck] (det[fi, ck] == "" ? "" : "; ") $16
    }
    $1 == "C" && $2 == sc { cv[fi, $3] = cv[fi, $3] " " $5 }
    function medrun(i, ck,   k, idx, c, v, j) {
      c = n[i, ck]
      for (k = 1; k <= c; k++) idx[k] = k
      for (k = 2; k <= c; k++) { v = idx[k]; j = k - 1; while (j >= 1 && rate[i, ck, idx[j]] + 0 > rate[i, ck, v] + 0) { idx[j + 1] = idx[j]; j-- } idx[j + 1] = v }
      return idx[int((c + 1) / 2)]
    }
    function cell(i, ck, what,   r, kk) {
      if (i in skip) return "not run"
      if (!n[i, ck]) return "-"
      r = medrun(i, ck)
      if (what == "rate") return spread(rates[i, ck])
      if (what == "p50") return num(p50[i, ck, r])
      if (what == "p99") return num(p99[i, ck, r])
      if (what == "fsync") return (forces[i, ck, r] > 0) ? sprintf("%.1f (%.2f ms)", ok[i, ck, r] / forces[i, ck, r], fsecs[i, ck, r] / forces[i, ck, r] * 1000) : "no fsync"
      if (what == "conv") { split(ck, kk, SUBSEP); return spread(cv[i, kk[2]]) }
    }
    END {
      names()
      H = (cfgname != "" ? "| " cfgname " " : "") "| " keyname " |"; S = (cfgname != "" ? "|---" : "") "|---:|"
      head("Requests/s"); head("p50 ms"); head("p99 ms")
      if (fsync) head("Writes per fsync (mean fsync)")
      if (conv) head("All nodes equal after, s")
      print H; print S
      nw = split("rate p50 p99" (fsync ? " fsync" : "") (conv ? " conv" : ""), what, " ")
      if (nk == 0) { line = (cfgname != "" ? "| - " : "") "| - |"; for (w = 1; w <= nw; w++) for (i = 1; i <= N; i++) line = line " " (i in skip ? "not run" : "-") " |"; print line }
      for (r = 1; r <= nk; r++) {
        ck = order[r]; split(ck, kk, SUBSEP)
        line = (cfgname != "" ? "| " kk[1] " " : "") "| " kk[2] " |"
        for (w = 1; w <= nw; w++) for (i = 1; i <= N; i++) line = line " " cell(i, ck, what[w]) " |"
        print line
      }
      notes = ""
      for (i = 1; i <= N; i++) {
        who = (N > 1 ? nm[i] ", " : "")
        if (i in skip) notes = notes "\n" (N > 1 ? nm[i] ": n" : "N") "ot run: " skip[i] "."
        for (r = 1; r <= nk; r++) {
          ck = order[r]; split(ck, kk, SUBSEP)
          if (err[i, ck] > 0) notes = notes "\nErrors, " who (cfgname != "" ? kk[1] ", " : "") tolower(keyname) " " kk[2] ": " err[i, ck] " of " tot[i, ck] " requests (" det[i, ck] ")."
        }
      }
      if (notes != "") print notes
    }' "${TSVS[@]}"
}

e_tables() {
  awk -F'\t' -v namestr="$NAMESTR" "$LIB"'
    FNR == 1 { fi++ }
    $1 == "E" {
      k = $2 SUBSEP $3
      if (!(k in seen)) { seen[k] = 1; order[++nk] = k }
      r = fi SUBSEP k SUBSEP $5
      if (!(r in runs)) { runs[r] = 1; rl[fi, k] = rl[fi, k] " " $5 }
      cpu[r] += $6; out[r] += $8; st[r] += $10; oth[r] += $12 + $13
      nk3 = $2 SUBSEP $3 SUBSEP $4
      if (!(nk3 in seen3)) { seen3[nk3] = 1; order3[++nn] = nk3 }
      ncpu[fi, nk3] = ncpu[fi, nk3] " " $6; nin[fi, nk3] = nin[fi, nk3] " " $7 / 1000; nout[fi, nk3] = nout[fi, nk3] " " $8 / 1000
    }
    $1 == "EW" { if (!(($2) in sz)) { sz[$2] = 1; sizes[++ns] = $2 }
      # EW size rep ok errors rate p50 p95 p99
      wr[fi, $2] = wr[fi, $2] " " $6; w50[fi, $2] = w50[fi, $2] " " $7; w99[fi, $2] = w99[fi, $2] " " $9
      wok[fi, $2] += $4; werr[fi, $2] += $5 }
    $1 == "EC" { ec[fi, $2] = ec[fi, $2] " " $4 }
    $1 == "EM" { em[fi, $2] = em[fi, $2] " " $4 / 1e6 }
    $1 == "EP" { ep[fi, $2] = ep[fi, $2] " " $4 }
    $1 == "EM2" { m1[fi, $2] = m1[fi, $2] " " $4; m2[fi, $2] = m2[fi, $2] " " $5; m3[fi, $2] = m3[fi, $2] " " $6 }
    function tot(i, k, which,   a, c, j, s, r) {
      c = split(rl[i, k], a, " "); s = ""
      for (j = 1; j <= c; j++) { r = i SUBSEP k SUBSEP a[j]
        s = s " " (which == "cpu" ? cpu[r] : which == "out" ? out[r] / 1000 : which == "st" ? st[r] : oth[r]) }
      return s
    }
    function win(p) { return p == "idle" ? "idle" : "writes" }
    END {
      names()
      print "CPU and node-to-node traffic, sum of the three nodes:"
      print ""
      H = "| Rows | Window |"; S = "|---:|---|"
      head("CPU, cores"); head("Peer traffic, KB/s")
      print H; print S
      for (r = 1; r <= nk; r++) {
        split(order[r], kk, SUBSEP); line = "| " kk[1] " | " win(kk[2]) " |"
        for (i = 1; i <= N; i++) line = line " " spread(tot(i, order[r], "cpu")) " |"
        for (i = 1; i <= N; i++) line = line " " spread(tot(i, order[r], "out")) " |"
        print line
      }
      print ""
      print "Sync requests answered per second, sum of the three nodes. A whole-database answer is one `/internal/state` response; other sync requests are `/internal/digests` and, in 2.1, `/internal/v2/*`. Write replication (`/internal/apply`) is not counted here."
      print ""
      H = "| Rows | Window |"; S = "|---:|---|"
      head("Whole-database answers/s"); head("Other sync requests/s")
      print H; print S
      for (r = 1; r <= nk; r++) {
        split(order[r], kk, SUBSEP); line = "| " kk[1] " | " win(kk[2]) " |"
        for (i = 1; i <= N; i++) line = line " " spread(tot(i, order[r], "st")) " |"
        for (i = 1; i <= N; i++) line = line " " spread(tot(i, order[r], "oth")) " |"
        print line
      }
      print ""
      print "Per node, median over the runs:"
      print ""
      H = "| Rows | Window | Node |"; S = "|---:|---|---|"
      head("CPU, cores"); head("In, KB/s"); head("Out, KB/s")
      print H; print S
      for (r = 1; r <= nn; r++) {
        split(order3[r], kk, SUBSEP); line = "| " kk[1] " | " win(kk[2]) " | " kk[3] " |"
        for (i = 1; i <= N; i++) line = line " " ((i, order3[r]) in ncpu ? num(median(ncpu[i, order3[r]])) : "-") " |"
        for (i = 1; i <= N; i++) line = line " " ((i, order3[r]) in nin ? num(median(nin[i, order3[r]])) : "-") " |"
        for (i = 1; i <= N; i++) line = line " " ((i, order3[r]) in nout ? num(median(nout[i, order3[r]])) : "-") " |"
        print line
      }
      print ""
      print "The writes, through n1, and what followed them:"
      print ""
      H = "| Rows |"; S = "|---:|"
      head("Writes/s"); head("p50 ms"); head("p99 ms"); head("All nodes equal after, s")
      print H; print S
      for (s = 1; s <= ns; s++) {
        z = sizes[s]; line = "| " z " |"
        for (i = 1; i <= N; i++) line = line " " spread(wr[i, z]) " |"
        for (i = 1; i <= N; i++) line = line " " spread(w50[i, z]) " |"
        for (i = 1; i <= N; i++) line = line " " spread(w99[i, z]) " |"
        for (i = 1; i <= N; i++) line = line " " spread(ec[i, z]) " |"
        print line
      }
      notes = ""
      for (i = 1; i <= N; i++) for (s = 1; s <= ns; s++) if (werr[i, sizes[s]] > 0)
        notes = notes "\nErrors, " (N > 1 ? nm[i] ", " : "") sizes[s] " rows: " werr[i, sizes[s]] " of " wok[i, sizes[s]] + werr[i, sizes[s]] " writes."
      if (notes != "") print notes
      print ""
      H = "| Rows |"; S = "|---:|"
      head("One whole-database answer, MB"); head("Preload until all nodes equal, s"); head("Memory at the end, MiB (n1 / n2 / n3)")
      print H; print S
      for (s = 1; s <= ns; s++) {
        z = sizes[s]; line = "| " z " |"
        for (i = 1; i <= N; i++) line = line " " spread(em[i, z]) " |"
        for (i = 1; i <= N; i++) line = line " " spread(ep[i, z]) " |"
        for (i = 1; i <= N; i++) line = line " " (((i, z) in m1) ? num(median(m1[i, z])) " / " num(median(m2[i, z])) " / " num(median(m3[i, z])) : "-") " |"
        print line
      }
    }' "${TSVS[@]}"
}

has() { cat "${TSVS[@]}" | awk -F'\t' -v s="$1" '(($1 == "L" || $1 == "S") && $2 == s) || ($1 == "E" && s == "e") { f = 1 } END { exit !f }'; }

report() {
  local i reps secs warm
  reps=$(fact reps); secs=$(fact secs); warm=$(fact warm)
  echo "### Machine and method"
  echo
  echo "- Machine: $(fact machine). Docker: $(fact docker). Containers see $(fact in_docker)."
  echo "- Disk: $(fact disk). Data volumes are on $(fact fs) under $(fact docker_root). One 4 KiB O_DSYNC write there (\`dd oflag=dsync\`, one write at a time, in a data volume of the image) took on average $(per_run dd_of), the least one \`fsync=always\` write can take.$( [ "$(fact host_os)" = Darwin ] && printf ' Whether the Docker VM passes the flush on to the SSD was not tested.')"
  for i in "${!DIRS[@]}"; do
    local d=${DIRS[$i]}
    echo "- ${NAMES[$i]}: image \`$(m "$d" image)\` ($(m "$d" image_arch), id $(m "$d" image_id | cut -c1-19)), version $(m "$d" version), built from commit $(m "$d" image_commit). Run from $(m "$d" start) to $(m "$d" end) (UTC) with the harness at commit $(m "$d" harness_commit)."
  done
  echo "- Other load: $(per_run load_of)."
  echo "- Memory caps: $(fact node_mem) per Serengeti node (the JVM's maximum heap is then $(fact heap) MiB), $(fact tools_mem) for the load generator. No CPU limits. Java: $(fact java)."
  echo "- Load generator: $(fact tool) from \`$(fact oha_image)\`, in a container on the same private Docker network as the nodes. HTTP/1.1 with keep-alive, Basic auth, one connection per concurrent request."
  echo "- Runs: each figure in scenarios a to d is from $reps run(s) of $secs s, each after a $warm s warm-up that is not counted, each on a new table. Requests/s is the median of the runs, with the lowest and highest in brackets; the latencies and fsync figures are those of the run with the median rate. Latency is the time from sending a request to the end of its response, as the load generator saw it. Errors (non-200 answers and failed requests) are listed under a table when there were any."
  echo "- Scenario e: $(fact e_reps) run(s) for each table size, each on a new cluster; each figure is the median of the runs, with the lowest and highest in brackets."
  echo "- Rows: \`{\"_id\": <uuid>, \"n\": <integer>, \"s\": <28-character string>}\`. After each insert run the table's row count is checked against the number of acknowledged inserts, and in a cluster every node must reach that count before the next run."
  echo "- Settings: defaults except \`SERENGETI_FSYNC\`, the cluster settings and TLS. The server keeps a verified password for 60 s, so a run may include one bcrypt check; the server answers on 16 threads, so concurrency 32 queues."
  if [ -n "$(fact node_args)" ]; then echo "- Extra arguments for every node (not the image default): \`$(fact node_args)\`."; fi
  echo "- Reproduce: \`tests/bench/run.sh\` (\`QUICK=1\` for a short run; \`IMAGE=name:tag\` for another image), then \`tests/bench/report.sh NAME=OUT ...\` to put runs side by side."
  echo
  if has a; then
    echo "### a. Single node, plain HTTP, fsync=always: INSERT"; echo
    latency_table a Concurrency "" fsync; echo
  fi
  if has a-tls; then
    echo "### a2. Single node, TLS, fsync=always: INSERT"; echo
    latency_table a-tls Concurrency "" fsync; echo
  fi
  if has b; then
    echo "### b. Single node, plain HTTP: INSERT with other fsync settings"; echo
    latency_table b Concurrency Config fsync; echo
  fi
  if has c; then
    echo "### c. Single node reads, concurrency $(fact read_conc)"; echo
    echo "\`SELECT * FROM t WHERE n = x\` scans the whole table (there are no indexes) and returns one row; x is a random value, the same sequence in every run."; echo
    latency_table c "Rows in table" Query; echo
  fi
  if has d; then
    echo "### d. Three-node TLS cluster, fsync=always, majority writes: INSERT through n1"; echo
    echo "The fsync figures are n1's. The last column is the time from the end of a run until all three nodes held its rows, with the counts read one node after the other about four times a second."; echo
    latency_table d Concurrency "" fsync converge; echo
  fi
  if has d-local; then
    echo "### d-local. Three-node TLS cluster, fsync=always, \`\"ack\": \"local\"\` writes: INSERT through n1"; echo
    echo "Each write is acknowledged once it is in n1's log; n2 and n3 get it through sync. Otherwise as d."; echo
    latency_table d-local Concurrency "" fsync converge; echo
  fi
  if has e; then
    echo "### e. Sync cost, three-node TLS cluster, table preloaded, $(fact e_rate) writes/s for $(fact e_secs) s through n1"; echo
    echo "Each run is a new cluster: the table is preloaded through n1 (1000 rows per INSERT), all three nodes must hold every row, then $(fact e_settle) s pass. The idle window ($(fact e_idle) s) has no client requests; the write window is $(fact e_rate) INSERTs per second, majority-acknowledged, with up to $(fact e_conc) in flight. Rates are over the window, from each container's cgroup CPU counter, its network counters and the node's \`/metrics\`. For n1 the load generator's own traffic is subtracted, so the traffic columns are node-to-node only."; echo
    e_tables; echo
  fi
}

if [ "${UPDATE_DOC:-0}" = 1 ]; then
  if ! grep -q '<!-- bench:begin -->' "$DOC" || ! grep -q '<!-- bench:end -->' "$DOC"; then
    echo "report: $DOC has no bench markers" >&2; exit 1
  fi
  tmp=$(mktemp)
  report > "$tmp"
  awk -v report="$tmp" '
    /<!-- bench:begin -->/ { print; while ((getline line < report) > 0) print line; skip = 1; next }
    /<!-- bench:end -->/ { skip = 0 }
    !skip { print }' "$DOC" > "$tmp.doc" && cp "$tmp.doc" "$DOC"
  cat "$tmp"; rm -f "$tmp" "$tmp.doc"
  echo "updated $DOC" >&2
else
  report
fi
