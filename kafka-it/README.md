# kafka-it — a local Kafka broker with every security mode at once

One broker, **seven listeners**, each configured differently: plaintext, TLS, mutual TLS, and four SASL combinations. Point a client at a port and you are testing that security mode — no reconfiguring, no restarts.

It exists because "does our Kafka client work with SASL_SSL and SCRAM-SHA-512?" is not a question you can answer against a plaintext broker, and standing one up per mode is a morning gone.

---

## Start here (60 seconds)

```bash
cd process/kafka-it
./start.sh
```

That is the whole setup. It issues certificates on first run, brings the stack up, waits for the broker, and creates the SCRAM account. Re-running is safe.

```bash
./stop.sh          # tear down; certificates are kept so the next start is instant
```

To verify it worked:

```bash
docker exec kafka_it kafka-broker-api-versions --bootstrap-server localhost:19092
```

Run the project's own 17-case test suite against it:

```bash
cd .. && ./run-kafka-matrix.sh
```

---

## The one thing that will trip you up

**Every listener is advertised as `host.docker.internal`.** Your client connects to `localhost:1909x`, the broker answers "talk to `host.docker.internal:1909x`", and your client goes there instead.

| Where your client runs | Works? | Why |
|---|---|---|
| Another **container** | ✅ | With `--add-host host.docker.internal:host-gateway` |
| Directly on the **host** (Mac/Windows) | ❌ | The host cannot resolve `host.docker.internal` |

A host-based client fails with **`TimeoutException: Timed out waiting for a node assignment`** — no handshake error, no auth error, nothing pointing at names. The first connection succeeds and then it hangs, which is what makes it so confusing.

**So: run clients in a container.** That is exactly what `run-kafka-matrix.sh` does, and why it exists.

<details>
<summary>Want to connect from the host anyway?</summary>

Add a hosts entry pointing `host.docker.internal` at loopback:

```
127.0.0.1  host.docker.internal
```

in `/etc/hosts` (needs sudo). The TLS certificate already lists `host.docker.internal` in its SAN, so certificate validation still passes. Without this entry, nothing on the host can complete a connection.
</details>

---

## The seven listeners

| Port | Protocol | Mechanism | Client needs |
|---|---|---|---|
| **19092** | `PLAINTEXT` | — | nothing |
| **19093** | `SSL` | — | truststore |
| **19094** | `SSL` | mutual TLS | truststore **+ keystore** |
| **19095** | `SASL_PLAINTEXT` | `PLAIN` | username + password |
| **19096** | `SASL_SSL` | `PLAIN` | truststore + username + password |
| **19097** | `SASL_PLAINTEXT` | `SCRAM-SHA-256` | username + password |
| **19098** | `SASL_SSL` | `SCRAM-SHA-512` | truststore + username + password |

Plus `29092` internally as `kafka-it:29092`, for containers on the same compose network.

**Work up the list.** If 19092 fails, nothing else will — and you have a networking problem, not a security one. If 19092 works and 19093 fails, it is TLS. If 19093 works and 19095 fails, it is authentication. Each step adds exactly one thing.

---

## Credentials and files

Everything is generated locally and throwaway. `secrets/` and `.env` are gitignored — **nothing here is a real credential**, and none of it is worth keeping. Delete both and re-run `./start.sh` to reissue.

### Where the values are

```bash
cat .env                        # STORE_PASSWORD, SASL_USER, SASL_PASSWORD
cat secrets/store-password      # the same store password, for tools that want a file
```

### What is in `secrets/`

| File | What it is | Give it to |
|---|---|---|
| `ca.crt` / `ca.key` | The local CA that signed everything | — |
| `broker.crt` / `broker.key` | The broker's own certificate | the broker |
| `broker.keystore.p12` | The broker's identity | the broker |
| `broker.truststore.jks` | Which clients the broker trusts | the broker (mTLS) |
| **`client.truststore.jks`** / `.p12` | **Trusts the CA — needed for any TLS listener** | your client |
| **`client.keystore.jks`** / `.p12` | **The client's identity — needed for mTLS (19094)** | your client |
| `client.crt` / `client.key` | The same identity as PEM | non-Java clients |
| `client.pkcs8.key` | The key in PKCS#8 | anything refusing PKCS#1 |
| `store-password` | The store password as a file | `KafkaSecurityMatrixIT`, which reads it from here |
| `keystore-creds`, `truststore-creds`, `key-creds` | **Unused.** See below | — |

All stores use the **same** password: `STORE_PASSWORD` from `.env`.

> **The three `*creds` files are dead.** They follow the Confluent image's `_CREDENTIALS`
> convention, which this compose file deliberately does not use — it sets the real Kafka property
> names and passes the password as `KAFKA_SSL_KEYSTORE_PASSWORD`, `KAFKA_SSL_KEY_PASSWORD` and
> `KAFKA_SSL_TRUSTSTORE_PASSWORD` instead. Nothing reads the files. The passwords are consequently
> in the broker's environment and visible to `docker inspect`, which is acceptable here and only
> here: every value is generated per run and thrown away by `stop.sh`.

The broker certificate's SAN covers `localhost`, `kafka-it`, `host.docker.internal` and `127.0.0.1`, so hostname verification passes on any of those names — no need to disable it.

---

## Connecting a client

Every example below assumes your client is in a container started with:

```bash
docker run --rm \
  --network process_default \
  --add-host host.docker.internal:host-gateway \
  -v "$PWD/secrets:/kafka-secrets:ro" \
  your-image
```

The three things that matter: **the network**, **the host-gateway mapping**, and **mounting `secrets/`** if you are using TLS.

### 19092 — PLAINTEXT

```properties
bootstrap.servers=host.docker.internal:19092
security.protocol=PLAINTEXT
```

### 19093 — SSL (server certificate only)

```properties
bootstrap.servers=host.docker.internal:19093
security.protocol=SSL
ssl.truststore.location=/kafka-secrets/client.truststore.jks
ssl.truststore.password=<STORE_PASSWORD>
ssl.truststore.type=JKS
```

### 19094 — SSL with mutual TLS

The client proves who it is as well, so it needs a keystore too.

```properties
bootstrap.servers=host.docker.internal:19094
security.protocol=SSL
ssl.truststore.location=/kafka-secrets/client.truststore.jks
ssl.truststore.password=<STORE_PASSWORD>
ssl.truststore.type=JKS
ssl.keystore.location=/kafka-secrets/client.keystore.jks
ssl.keystore.password=<STORE_PASSWORD>
ssl.keystore.type=JKS
ssl.key.password=<STORE_PASSWORD>
```

> `ssl.key.password` is the password of the **key inside** the keystore, which is separate from the keystore's own password. They happen to be identical here. A keystore that opens but fails with `UnrecoverableKeyException` means these two have been mixed up.

### 19095 — SASL_PLAINTEXT with PLAIN

```properties
bootstrap.servers=host.docker.internal:19095
security.protocol=SASL_PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required \
  username="<SASL_USER>" password="<SASL_PASSWORD>";
```

### 19096 — SASL_SSL with PLAIN

```properties
bootstrap.servers=host.docker.internal:19096
security.protocol=SASL_SSL
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required \
  username="<SASL_USER>" password="<SASL_PASSWORD>";
ssl.truststore.location=/kafka-secrets/client.truststore.jks
ssl.truststore.password=<STORE_PASSWORD>
ssl.truststore.type=JKS
```

### 19097 — SASL_PLAINTEXT with SCRAM-SHA-256

```properties
bootstrap.servers=host.docker.internal:19097
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-256
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
  username="<SASL_USER>" password="<SASL_PASSWORD>";
```

### 19098 — SASL_SSL with SCRAM-SHA-512

The strictest listener, and the closest to a managed cloud broker.

```properties
bootstrap.servers=host.docker.internal:19098
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
  username="<SASL_USER>" password="<SASL_PASSWORD>";
ssl.truststore.location=/kafka-secrets/client.truststore.jks
ssl.truststore.password=<STORE_PASSWORD>
ssl.truststore.type=JKS
```

Substitute `<SASL_USER>`, `<SASL_PASSWORD>` and `<STORE_PASSWORD>` from `.env`.

---

## Using it from the ETL Console

The console stores these as **Kafka connection profiles** (Settings → Kafka), so you do not hand-write properties — you fill in the dialog and press *Test connection*.

1. Upload `ca.crt` under **Certificates**, then press **Generate truststore**. The server builds a PKCS12 and keeps its password encrypted; you never see or type it.
2. For mTLS (19094), also upload `client.crt` and **`client.pkcs8.key`** — not `client.key`. The server reads PKCS#8 only and refuses PKCS#1 by name, with the `openssl` command to convert it.
3. Set bootstrap servers to `host.docker.internal:<port>` — the console's backend runs in a container, so it resolves that name.
4. Press **Test connection**.

Generated stores are PKCS12, so the profile's store type must say `PKCS12`. The console sets this from the file extension automatically.

---

## Smoke test without any client

Straight from inside the broker container, which bypasses all the networking above:

```bash
docker exec kafka_it kafka-topics --bootstrap-server localhost:19092 --list

docker exec kafka_it kafka-topics --bootstrap-server localhost:19092 \
  --create --topic smoke --partitions 1 --replication-factor 1

docker exec -i kafka_it kafka-console-producer --bootstrap-server localhost:19092 --topic smoke
# type a line, then Ctrl-D

docker exec kafka_it kafka-console-consumer --bootstrap-server localhost:19092 \
  --topic smoke --from-beginning --timeout-ms 5000
```

If this works and your client does not, the problem is your client's configuration or its networking — not the broker.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `TimeoutException: Timed out waiting for a node assignment` | Client cannot resolve `host.docker.internal` | Run the client in a container with `--add-host host.docker.internal:host-gateway` |
| `SSLHandshakeException: unable to find valid certification path` | No truststore, or the wrong one | Point `ssl.truststore.location` at `client.truststore.jks` |
| `SSLHandshakeException` on **19094** only | Broker wants a client certificate | Add the keystore properties — 19094 is mutual TLS |
| `UnrecoverableKeyException` | `ssl.key.password` wrong | It is the key's password, not the store's — both are `STORE_PASSWORD` here |
| `Authentication failed: Invalid username or password` | Credentials do not match the broker | `cat .env`. If you regenerated `.env` while the broker was running, restart it — its JAAS config is baked in at startup |
| SCRAM fails on 19097/19098 but PLAIN works on 19095 | SCRAM account missing | `./start.sh` creates it in ZooKeeper. Re-run it |
| Every SASL case fails on an otherwise fine setup | JDK 24+ | `kafka-clients` 2.5 authenticates through `Subject.getSubject`, removed with the SecurityManager. Use JDK 17 — `run-kafka-matrix.sh` pins it |
| `Assignments can only be reset if the group is inactive` | Resetting offsets with consumers attached | Stop the consumers, wait ~30s for the session timeout, then reset |
| Certificate errors after changing hostnames | SAN does not cover the new name | Delete `secrets/` and `.env`, re-run `./start.sh` |

### Reading the broker's own view

```bash
docker logs --tail 50 kafka_it
docker logs kafka_it 2>&1 | grep -i "authentication\|ssl"
```

Authentication and handshake failures are logged broker-side with the client's address, which is often faster than reading the client's stack trace.

---

## What is in this folder

| File | Purpose |
|---|---|
| `start.sh` | Issues certificates if missing, starts the stack, waits, creates the SCRAM account. Idempotent |
| `stop.sh` | `docker compose down -v`. Leaves `secrets/` in place |
| `generate-certs.sh` | Issues the CA, broker and client material. Called by `start.sh`; run directly only to force reissue |
| `docker-compose.kafka-it.yml` | The broker and ZooKeeper, with all seven listeners |
| `.env` | `STORE_PASSWORD`, `SASL_USER`, `SASL_PASSWORD`. Generated, gitignored |
| `secrets/` | Certificates and stores. Generated, gitignored |

Containers are named `kafka_it` and `kafka_it_zookeeper`, so they will not collide with the application stack's `kafka` and `zookeeper`.

## Starting completely fresh

```bash
./stop.sh
rm -rf secrets .env
./start.sh
```

Everything is reissued. Any client holding the old truststore will need the new `ca.crt`.
