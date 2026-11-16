# Serengeti on Kubernetes

[serengeti.yaml](serengeti.yaml) runs a three-node cluster as a StatefulSet: TLS between the nodes and for
clients, a volume per pod, a ClusterIP Service for clients, and a disruption budget that lets one pod go at a
time. Nothing is reachable from outside the cluster.

## Steps

1. Certificates, once, with the image's own tool. Each pod is `serengeti-N`, and its certificate also names
   `serengeti-N.serengeti` (how the other nodes reach it) and `serengeti-client` (the Service):

   ```sh
   mkdir certs && docker run --rm --user "$(id -u)" -v "$PWD/certs:/certs" --entrypoint serengeti-certs \
     ataiva/serengeti:latest \
     serengeti-0=serengeti-0.serengeti,serengeti-client \
     serengeti-1=serengeti-1.serengeti,serengeti-client \
     serengeti-2=serengeti-2.serengeti,serengeti-client
   kubectl create secret generic serengeti-certs --from-file=certs/
   ```

   The CA's private key is deleted once the certificates are made. Keep `certs/ca.pem` for clients.

2. The admin password and the cluster key (at least 32 characters), each in a file of its own:

   ```sh
   kubectl create secret generic serengeti-secrets \
     --from-file=admin-password=./admin-password --from-file=cluster-key=./cluster-key
   ```

3. `kubectl apply -f serengeti.yaml`, then `kubectl rollout status statefulset/serengeti`. A pod is ready
   once it can take a write: caught up with the cluster and a majority reachable.

Applications in the cluster use `https://serengeti-client:1985` and trust `ca.pem`; the
[clients](../../clients/) take a list of URLs, so they can also name the pods. From your own machine, use
`kubectl port-forward service/serengeti-client 1985` and `https://localhost:1985` (add `localhost` to the
certificate names in step 1 for that). In a pod, `kubectl exec -it serengeti-0 -- serengeti shell` signs in
as the admin with the password from its secret.

## Notes

- In another namespace than `default`, nothing changes: the short names resolve within the namespace.
- To run 5 or 7 nodes, set `replicas` and the `NODES` variable together, and make a certificate for each pod.
  Writes need a majority of `NODES`.
- `resources.limits.memory` sizes the node: the heap is 60% of it, and writes that add data are refused once
  the data is estimated to fill 60% of the heap. Each node holds all the data.
- Backups: `GET /v1/backup` through the port-forward, as in [docs/API.md](../../docs/API.md#backup).

[run.sh](run.sh) runs these steps on a throwaway kind cluster and checks the result: the pods become ready, a
write through the Service is on every pod, a deleted pod comes back with its data and the write it missed,
the shell works in a pod, and no Service is a NodePort or LoadBalancer. CI runs it.
