# Monitoring Serengeti with Prometheus and Grafana

[compose.yaml](compose.yaml) adds Prometheus and Grafana to the three-node cluster of
[../cluster](../cluster/compose.yaml). Prometheus scrapes `GET /metrics` on every node over HTTPS as an
account with the `metrics` role and evaluates [alerts.yml](alerts.yml); Grafana shows the dashboard
[serengeti.json](grafana/dashboards/serengeti.json). The series are listed in
[docs/API.md](../../docs/API.md#metrics).

## Steps

The comments at the top of `compose.yaml` have the same steps.

1. A file named `.env` next to `compose.yaml`, never committed:

   ```
   SERENGETI_ADMIN_PASSWORD=a long admin password
   SERENGETI_CLUSTER_KEY=a random string of at least 32 characters
   SERENGETI_METRICS_PASSWORD=a password of 12 to 72 bytes for the account Prometheus uses
   GRAFANA_ADMIN_PASSWORD=a long password for Grafana's admin user
   ```

2. `docker compose run --rm certs` makes the certificates, once.
3. `docker compose up -d` starts the nodes, Prometheus and Grafana.
4. `docker compose run --rm metrics-account` makes the account `metrics` with the `metrics` role on n1; the
   cluster copies it to the others within seconds. Run it again after changing
   `SERENGETI_METRICS_PASSWORD`, then `docker compose restart prometheus`.
5. Grafana is at http://127.0.0.1:13000 (user `admin`), Prometheus at http://127.0.0.1:19090.

Both are published on 127.0.0.1 only, like the nodes. Prometheus has no login of its own, so do not publish
it further; from another machine, use an SSH tunnel (`ssh -L 13000:127.0.0.1:13000 the-host`).

## The alerts

| Alert | Fires when | For |
|---|---|---|
| SerengetiDown | Prometheus cannot scrape a node | 1 min |
| SerengetiNotReady | a node answers 503 to `/ready` | 2 min |
| SerengetiDiskFailed | a write to disk failed; the node needs a restart | at once |
| SerengetiNoMajority | a node reaches fewer nodes than a majority; its majority writes and reads get 503 | 1 min |
| SerengetiPeerUnreachable | a node does not reach every other node | 5 min |
| SerengetiStale | a node was away longer than `tombstone_ttl_hours` and takes no writes | at once |
| SerengetiMemoryFull | a node refuses writes that add data | at once |
| SerengetiMemoryHigh | a node holds more than 85% of the data it may hold | 10 min |
| SerengetiServerErrors | a node answered a 5xx other than 503 | 1 min |
| SerengetiSlowFsync | forcing the log to disk takes over 50 ms on average | 10 min |
| SerengetiLoginsLimited | a node refused logins from an address with too many failed attempts | at once |
| SerengetiRestarts | a node started 3 or more times in 30 minutes | at once |
| SerengetiConflicts | a node holds rows with conflicting versions (`SHOW CONFLICTS`) | 1 hour |
| SerengetiVersionsDiffer | the nodes run different versions | 2 hours |

503 is left out of SerengetiServerErrors on purpose: a node answers it while it stops, without a majority
and when it is full, and those have alerts of their own. The thresholds are starting points; change them in
`alerts.yml` and run its tests:

```sh
docker run --rm -v "$PWD:/m:ro" -w /m --entrypoint promtool prom/prometheus:v3.5.0 test rules alerts.test.yml
```

The example evaluates the alerts but sends them nowhere. To be told, add an Alertmanager and an
`alerting:` section to [prometheus.yml](prometheus.yml).

## Notes

- The `metrics` role may use `GET /metrics`, `GET /v1/status` and change its own password, and gets 403
  for everything else, so the password Prometheus holds cannot read or change the data.
- With nodes on separate hosts, list each host in `static_configs` of `prometheus.yml` and make the
  certificates with those names (`serengeti-certs n1=host1.example.com ...`).
- Prometheus keeps 15 days of samples in its volume (`--storage.tsdb.retention.time`).

[run.sh](run.sh) runs these steps with a built image and checks the result: the rules' unit tests pass,
Prometheus scrapes the three nodes and loads 14 rules, the account cannot read or write data, Grafana
refuses a wrong password, has the dashboard and answers every one of its queries, SerengetiDown is raised
for a stopped node, and every published port is on 127.0.0.1.
