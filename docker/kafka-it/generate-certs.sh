#!/usr/bin/env bash
# Builds the certificate material the local Kafka security stack needs.
#
# Everything here is generated fresh and lives only under ./secrets, which is gitignored -- the
# CA, the broker's identity and the client's are all throwaway, and none of it is ever committed.
# Regenerating is the supported way to recover: there is nothing here worth keeping.
#
# The client side is deliberately produced in BOTH PKCS12 and JKS. The application generates
# PKCS12 while somebody who ran keytool by hand will have JKS, and the client has to declare the
# right ssl.*.type for each -- that is the case worth testing, not one of them.
#
# Author: Nabeel Ahmed
set -euo pipefail
cd "$(dirname "$0")"

OUT="secrets"
DAYS=3650
# Test-only, generated per run, never committed. The broker needs the same value in a file it can
# read, which is why it is written out rather than held in the shell.
PASS="$(openssl rand -hex 16)"

rm -rf "${OUT}" && mkdir -p "${OUT}"
echo "${PASS}" > "${OUT}/store-password"
chmod 600 "${OUT}/store-password"

# --- the CA everything else chains to -----------------------------------------------------
openssl req -new -x509 -nodes -days ${DAYS} -newkey rsa:2048 \
  -subj "/CN=kafka-it-ca/O=ETL Console local test" \
  -keyout "${OUT}/ca.key" -out "${OUT}/ca.crt" 2>/dev/null

# --- broker identity ----------------------------------------------------------------------
# SAN matters: the client verifies the hostname by default, and it must cover every name the
# broker is reached by. host.docker.internal is how the application container reaches it, and
# leaving that out failed every TLS profile with "handshake failed" -- which was the check doing
# its job, not a broken certificate.
openssl req -new -nodes -newkey rsa:2048 \
  -subj "/CN=localhost/O=ETL Console local test" \
  -keyout "${OUT}/broker.key" -out "${OUT}/broker.csr" 2>/dev/null
openssl x509 -req -in "${OUT}/broker.csr" -CA "${OUT}/ca.crt" -CAkey "${OUT}/ca.key" \
  -CAcreateserial -days ${DAYS} -out "${OUT}/broker.crt" \
  -extfile <(printf "subjectAltName=DNS:localhost,DNS:kafka-it,DNS:host.docker.internal,IP:127.0.0.1\nextendedKeyUsage=serverAuth") 2>/dev/null

openssl pkcs12 -export -in "${OUT}/broker.crt" -inkey "${OUT}/broker.key" \
  -chain -CAfile "${OUT}/ca.crt" -name broker \
  -out "${OUT}/broker.keystore.p12" -passout "pass:${PASS}" 2>/dev/null
keytool -importcert -noprompt -alias ca -file "${OUT}/ca.crt" \
  -keystore "${OUT}/broker.truststore.jks" -storetype JKS \
  -storepass "${PASS}" >/dev/null 2>&1

# --- client identity, for mutual TLS -------------------------------------------------------
openssl req -new -nodes -newkey rsa:2048 \
  -subj "/CN=etl-console-client/O=ETL Console local test" \
  -keyout "${OUT}/client.key" -out "${OUT}/client.csr" 2>/dev/null
openssl x509 -req -in "${OUT}/client.csr" -CA "${OUT}/ca.crt" -CAkey "${OUT}/ca.key" \
  -CAcreateserial -days ${DAYS} -out "${OUT}/client.crt" \
  -extfile <(printf "extendedKeyUsage=clientAuth") 2>/dev/null

# The key the application's upload path accepts: unencrypted PKCS#8, not openssl's default PKCS#1.
openssl pkcs8 -topk8 -nocrypt -in "${OUT}/client.key" -out "${OUT}/client.pkcs8.key" 2>/dev/null

# --- client stores, one of each format ------------------------------------------------------
openssl pkcs12 -export -in "${OUT}/client.crt" -inkey "${OUT}/client.key" \
  -chain -CAfile "${OUT}/ca.crt" -name client \
  -out "${OUT}/client.keystore.p12" -passout "pass:${PASS}" 2>/dev/null
keytool -importkeystore -noprompt \
  -srckeystore "${OUT}/client.keystore.p12" -srcstoretype PKCS12 -srcstorepass "${PASS}" \
  -destkeystore "${OUT}/client.keystore.jks" -deststoretype JKS -deststorepass "${PASS}" >/dev/null 2>&1

keytool -importcert -noprompt -alias ca -file "${OUT}/ca.crt" \
  -keystore "${OUT}/client.truststore.p12" -storetype PKCS12 \
  -storepass "${PASS}" >/dev/null 2>&1
keytool -importcert -noprompt -alias ca -file "${OUT}/ca.crt" \
  -keystore "${OUT}/client.truststore.jks" -storetype JKS \
  -storepass "${PASS}" >/dev/null 2>&1

# The broker reads its passwords from files rather than the environment, so they stay out of
# `docker inspect` and the process list.
for f in keystore-creds key-creds truststore-creds; do echo "${PASS}" > "${OUT}/${f}"; done
chmod 600 "${OUT}"/*creds "${OUT}"/*.key

# The SASL account the SASL listeners accept. Generated with everything else so no password in
# this stack is ever a value somebody typed and might reuse.
SASL_PASS="$(openssl rand -hex 16)"

# Written beside the compose file because docker compose substitutes from .env in its own
# directory. Gitignored along with secrets/.
cat > .env <<ENVFILE
STORE_PASSWORD=${PASS}
SASL_USER=etl
SASL_PASSWORD=${SASL_PASS}
ENVFILE
chmod 600 .env

rm -f "${OUT}"/*.csr "${OUT}"/*.srl
echo "Generated into $(pwd)/${OUT}:"
ls -1 "${OUT}" | sed 's/^/  /'
