#!/usr/bin/env python3
"""MIG-132: run process as two instances and prove the ten wave-zero assertions.

Standard library only. Driven by run.sh, which builds the process image first; see README.md.

Every check prints one line, "T<n> PASS|FAIL|SKIP <evidence>", and the run ends with the table and
exit status 0 only when every selected check passed. The per-run secrets (database passwords, JWT key,
encryption key, service token) are generated here, kept in .run/env (git-ignored, mode 0600) and never
printed. Seeded rows live in tenant 9132001 of a database that exists only for this run.
"""
import argparse
import base64
import datetime as dt
import hashlib
import hmac
import http.client
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
RUN_DIR = os.path.join(HERE, ".run")
ENV_FILE = os.path.join(RUN_DIR, "env")
PROJECT = "mig132"

TENANT = 9132001
PLATFORM_ADMIN = 9132100
TENANT_ADMIN = 9132101
TENANT_USER = 9132102
T5_USER = 9132103
T1_JOBS = 200
T1_FIRST_JOB = 9133000
T7_JOB = 9134001

ALL_CHECKS = ["T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "T9", "T10"]

# Mutations: each gives a replica per-instance state back (or unaligns a schedule) through
# configuration alone, so the image under test is the real one. "expect" is what must go red.
MUTATIONS = {
    "unlocked-enqueuer": {
        "env": {},
        "image": "process-two-instance:mutant-t1",  # mutants/build-unlocked-enqueuer.sh
        "sql": "DROP INDEX IF EXISTS ux_job_queue_one_in_flight_per_job;",
        "expect": ["T1"],
        "about": "the enqueuer's claim without FOR UPDATE SKIP LOCKED and without the V83 one-run-per-job index",
    },
    "split-login-guard": {
        "env": {"H_LOGIN_GUARD_PREFIX_A": "identity:login-guard:a:", "H_LOGIN_GUARD_PREFIX_B": "identity:login-guard:b:"},
        "expect": ["T5"],
        "about": "A and B count failed sign-ins under different keys: the per-JVM guard, back",
    },
    "split-redis": {
        "env": {"H_REDIS_HOST_B": "redis2"},
        "expect": ["T4", "T5", "T6"],
        "about": "process B on a Redis of its own: every shared cache version and counter is per instance again",
    },
    "unaligned-reconcile": {
        # Every other minute, 40 s after A's tick: past A's 30 s lock, so both sweep in that minute --
        # two replicas on unrelated schedules, the fixedDelay defect. (B on every minute at :40 would
        # not show it: its own lock would then hold A off, and one sweep a minute would still run.)
        "env": {"H_RECONCILE_CRON_B": "40 */2 * * * *"},
        "expect": ["T7"],
        "about": "B sweeps on its own schedule, 40 s after A's tick: the fixedDelay defect, reproduced",
    },
    "split-broadcast": {
        "env": {"H_REDIS_HOST_NOTIF_B": "redis2"},
        "expect": ["T2", "T3"],
        "about": "notifications B on a Redis of its own: the STOMP broker is per instance again",
    },
    "unaligned-close": {
        "env": {"H_CLOSE_CRON_B": "30 1-59/2 * * * *"},
        "expect": ["T10"],
        "about": "billing B closes on its own tick, 90 s after A's: two closes per period",
    },
}

results = {}
started = time.time()


# ---------------------------------------------------------------------------------------------- utils

def log(msg):
    print("[%6.1fs] %s" % (time.time() - started, msg), flush=True)


def record(check, ok, evidence):
    status = "PASS" if ok is True else ("SKIP" if ok is None else "FAIL")
    results[check] = (status, evidence)
    print("%s %s %s" % (check, status, evidence), flush=True)


def run(cmd, env=None, check=True, input_text=None, timeout=600, cwd=None):
    proc = subprocess.run(cmd, env=env, input=input_text, capture_output=True, text=True, timeout=timeout, cwd=cwd)
    if check and proc.returncode != 0:
        raise RuntimeError("command failed (%d): %s\n%s%s" % (proc.returncode, " ".join(cmd[:6]), proc.stdout[-2000:], proc.stderr[-2000:]))
    return proc


def load_env(fresh):
    """This run's secrets: generated once per run, read back by later invocations with --reuse."""
    os.makedirs(RUN_DIR, exist_ok=True)
    if fresh or not os.path.exists(ENV_FILE):
        values = {
            "H_PG_PASSWORD": secrets.token_urlsafe(24),
            "H_ANALYTICS_OWNER_PASSWORD": secrets.token_urlsafe(24),
            "H_ANALYTICS_APP_PASSWORD": secrets.token_urlsafe(24),
            "H_BILLING_OWNER_PASSWORD": secrets.token_urlsafe(24),
            "H_BILLING_APP_PASSWORD": secrets.token_urlsafe(24),
            "H_INTERNAL_TOKEN": secrets.token_urlsafe(32),
            "H_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
            "H_JWT_SECRET": base64.b64encode(secrets.token_bytes(32)).decode(),
            "H_METER_KEY": secrets.token_urlsafe(24),
            "H_RUN_ID": uuid.uuid4().hex[:8],
        }
        fd = os.open(ENV_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as out:
            for k, v in values.items():
                out.write("%s=%s\n" % (k, v))
    values = {}
    with open(ENV_FILE) as source:
        for line in source:
            if "=" in line:
                k, v = line.rstrip("\n").split("=", 1)
                values[k] = v
    return values


class Harness:
    def __init__(self, args):
        self.args = args
        self.secrets = load_env(fresh=not args.reuse)
        self.env = dict(os.environ)
        self.env.update(self.secrets)
        self.env["PROCESS_IMAGE"] = args.image
        self.env["LB_IMAGE"] = self.lb_image()
        self.mutation = MUTATIONS.get(args.mutate) if args.mutate else None
        if self.mutation:
            self.env.update(self.mutation["env"])
            if self.mutation.get("image"):
                self.env["PROCESS_IMAGE"] = self.mutation["image"]
        self.run_id = self.secrets["H_RUN_ID"]
        self.ports = {"lb": 19198, "a": 19101, "b": 19102, "notif-a": 19111, "notif-b": 19112}

    # -- docker

    def lb_image(self):
        if os.environ.get("LB_IMAGE"):
            return os.environ["LB_IMAGE"]
        # Anything with nginx will do; the official image first. Offline machines fall back to a local
        # image built on nginx:alpine rather than pulling one.
        for image in ("nginx:1.27-alpine", "nginx:alpine", "nginx:latest", "scheduler1-app:latest", "next-app:latest"):
            if subprocess.run(["docker", "image", "inspect", image], capture_output=True).returncode == 0:
                return image
        return "nginx:1.27-alpine"

    def compose(self, *args, check=True, timeout=900):
        profiles = []
        for p in ("notifications", "analytics", "billing", "mutant"):
            profiles += ["--profile", p]
        return run(["docker", "compose", "-p", PROJECT, "-f", os.path.join(HERE, "compose.yml")] + profiles + list(args),
                   env=self.env, check=check, timeout=timeout)

    def psql(self, sql, db="etl_job"):
        proc = run(["docker", "exec", "-i", "mig132-postgres", "psql", "-U", "harness", "-d", db, "-At", "-v", "ON_ERROR_STOP=1", "-q"],
                   input_text=sql)
        return proc.stdout.strip()

    def logs(self, container, since=None):
        cmd = ["docker", "logs", "-t"]
        if since:
            cmd += ["--since", since]
        cmd.append(container)
        proc = run(cmd, check=False)
        return proc.stdout + proc.stderr

    def inspect(self, container, fmt):
        return run(["docker", "inspect", "-f", fmt, container], check=False).stdout.strip()

    def wait_healthy(self, containers, timeout=300):
        deadline = time.time() + timeout
        pending = list(containers)
        while pending and time.time() < deadline:
            for c in list(pending):
                state = self.inspect(c, "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}")
                if state.endswith("healthy") and not state.endswith("unhealthy"):
                    pending.remove(c)
                elif state.startswith("exited") or state.startswith("dead"):
                    raise RuntimeError("%s stopped while starting: %s\n%s" % (c, state, self.logs(c)[-3000:]))
            time.sleep(1)
        if pending:
            raise RuntimeError("not healthy in %ds: %s" % (timeout, pending))

    # -- http

    def token(self, app_user_id, role, tenant, username):
        b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=")
        now = int(time.time())
        claims = {"sub": username, "type": "access", "appUserId": app_user_id, "userRole": role, "iat": now, "exp": now + 3600}
        if tenant is not None:
            claims["tenantId"] = tenant
        head = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode()) + b"." + b64(json.dumps(claims).encode())
        key = base64.b64decode(self.secrets["H_JWT_SECRET"])
        return (head + b"." + b64(hmac.new(key, head, hashlib.sha256).digest())).decode()

    def http(self, method, port, path, token=None, body=None, timeout=20):
        url = "http://127.0.0.1:%d/api/v1%s" % (port, path)
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    raw = resp.read().decode()
                    return resp.status, raw, dict(resp.headers)
            except urllib.error.HTTPError as err:
                return err.code, err.read().decode(), dict(err.headers)
            except (urllib.error.URLError, http.client.RemoteDisconnected, ConnectionError) as err:
                # A connection dropped before any answer (seen once, right after an instance reported
                # healthy): asked again rather than failing the check on transport.
                if attempt == 2:
                    raise
                log("%s %s: %s -- retrying" % (method, url, err))
                time.sleep(1)

    # ------------------------------------------------------------------------------------ lifecycle

    def down(self):
        self.compose("down", "-v", "--remove-orphans", check=False)

    def up_infra(self):
        log("infra: postgres, redis, kafka (fresh)")
        services = ["postgres", "redis", "kafka"] + (["redis2"] if self.mutation else [])
        self.compose("up", "-d", *services)
        self.wait_healthy(["mig132-postgres", "mig132-redis", "mig132-kafka"], 180)

    # ------------------------------------------------------------------------------------ phase P

    def phase_process(self, checks):
        # T9, first half: both instances boot together against an EMPTY database, so their Liquibase
        # runs race for the changelog lock and both ApplicationRunners run at once.
        log("process: A and B booting together on an empty database")
        t0 = time.time()
        self.compose("up", "-d", "--no-deps", "process-a", "process-b")
        boot_fresh = self.boot_evidence(t0)
        self.changelog = self.psql("SELECT count(*) || ' changesets by ' || count(DISTINCT deployment_id) || ' deployment(s)' "
                                   "FROM databasechangelog")
        self.compose("up", "-d", "--no-deps", "lb")
        self.seed_people()
        if self.mutation and self.mutation.get("sql"):
            self.psql(self.mutation["sql"])
        self.lb_alternates()

        if "T1" in checks or "T9" in checks:
            # T9, second half, and the setup for T1: both instances stopped, T1's due work written,
            # both started together -- so their enqueuers wake within a second of each other with 200
            # slots due, which is the contention T1 is about.
            log("process: stopping both, seeding %d due slots, starting both together" % T1_JOBS)
            self.compose("stop", "process-a", "process-b")
            self.seed_t1()
            t0 = time.time()
            self.compose("start", "process-a", "process-b")
            boot_again = self.boot_evidence(t0)
            self.check_t9(boot_fresh, boot_again)
            if "T1" in checks:
                self.check_t1()
        if "T5" in checks:
            self.check_t5()
        if "T4" in checks:
            self.check_t4()
        if "T6" in checks:
            self.check_t6()
        if "T7" in checks:
            self.check_t7()

    def boot_evidence(self, t0):
        try:
            self.wait_healthy(["mig132-process-a", "mig132-process-b"], 300)
            ok = True
        except RuntimeError as err:
            ok = False
            log(str(err)[:2000])
        took = time.time() - t0
        restarts = {c: self.inspect(c, "{{.RestartCount}} {{.State.Status}}") for c in ("mig132-process-a", "mig132-process-b")}
        return ok, took, restarts

    def lb_alternates(self):
        seen = []
        for _ in range(6):
            status, _, headers = self.http("GET", self.ports["lb"], "/actuator/health")
            seen.append(headers.get("X-Upstream", "?"))
        distinct = len(set(seen))
        alternating = all(seen[i] != seen[i + 1] for i in range(len(seen) - 1))
        log("lb: 6 requests answered by %s -- %s" % (seen, "round-robin, not sticky" if distinct == 2 and alternating else "NOT alternating"))
        self.lb_evidence = (distinct == 2 and alternating, seen)

    def seed_people(self):
        # A hash of nobody's password: bcrypt-shaped, so matches() answers false rather than erroring.
        nobody = "$2a$10$" + base64.b64encode(secrets.token_bytes(40)).decode().replace("+", ".")[:53]
        self.psql("""
            INSERT INTO tenant (tenant_id, date_created, status, tenant_code, tenant_name, uuid)
            VALUES ({t}, now(), 'Active', 'MIG132', 'MIG-132 two-instance harness', '{u}')
            ON CONFLICT DO NOTHING;
            INSERT INTO app_user (app_user_id, date_created, full_name, password, status, tenant_id, user_role, username, uuid)
            VALUES ({pa}, now(), 'Harness Platform Admin', '{pw}', 'Active', NULL, 'PLATFORM_ADMIN', 'platform-admin@mig132.invalid', '{u1}'),
                   ({ta}, now(), 'Harness Tenant Admin', '{pw}', 'Active', {t}, 'TENANT_ADMIN', 'tenant-admin@mig132.invalid', '{u2}'),
                   ({tu}, now(), 'Harness Tenant User', '{pw}', 'Active', {t}, 'TENANT_USER', 'tenant-user@mig132.invalid', '{u3}')
            ON CONFLICT DO NOTHING;
        """.format(t=TENANT, pa=PLATFORM_ADMIN, ta=TENANT_ADMIN, tu=TENANT_USER, pw=nobody,
                   u=uuid.uuid4(), u1=uuid.uuid4(), u2=uuid.uuid4(), u3=uuid.uuid4()))
        self.admin = self.token(TENANT_ADMIN, "TENANT_ADMIN", TENANT, "tenant-admin@mig132.invalid")
        self.platform = self.token(PLATFORM_ADMIN, "PLATFORM_ADMIN", None, "platform-admin@mig132.invalid")
        self.user = self.token(TENANT_USER, "TENANT_USER", TENANT, "tenant-user@mig132.invalid")

    def seed_t1(self):
        # Chicago wall-clock, as process reads it: naive timestamps are America/Chicago (TimeZone.setDefault).
        self.psql("""
            INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id,
                                    created_by, complete_job, fail_job, skip_job, max_attempts)
            SELECT {first} + g, now() AT TIME ZONE 'America/Chicago', 'Auto', 'mig132-t1-' || g, 'Active', 1, {t},
                   {ta}, false, false, false, 1
              FROM generate_series(1, {n}) g;
            INSERT INTO scheduler (scheduler_id, date_created, frequency, job_id, start_date, start_time, next_run_at,
                                   expired, dispatch_eligible)
            SELECT {first} + g, now() AT TIME ZONE 'America/Chicago', 'Daily', {first} + g,
                   (now() AT TIME ZONE 'America/Chicago')::date,
                   ((now() AT TIME ZONE 'America/Chicago') - interval '1 minute')::time,
                   date_trunc('second', (now() AT TIME ZONE 'America/Chicago') - interval '1 minute'),
                   false, true
              FROM generate_series(1, {n}) g;
        """.format(first=T1_FIRST_JOB, n=T1_JOBS, t=TENANT, ta=TENANT_ADMIN))

    # ------------------------------------------------------------------------------------ checks

    def check_t9(self, fresh, again):
        ok = fresh[0] and again[0] and all(r.startswith("0 running") for r in list(fresh[2].values()) + list(again[2].values()))
        record("T9", ok, "both healthy booting together on an empty database (%.0fs, concurrent Liquibase) and again "
               "together with 200 slots due (%.0fs); restarts/state %s; changelog: %s -- one instance "
               "applied it while the other held off, and both started" % (fresh[1], again[1], again[2], self.changelog))

    def check_t1(self):
        log("T1: waiting for the enqueuers")
        first, last = T1_FIRST_JOB + 1, T1_FIRST_JOB + T1_JOBS
        deadline = time.time() + 150
        while time.time() < deadline:
            done = int(self.psql("SELECT count(DISTINCT job_id) FROM job_queue WHERE job_id BETWEEN %d AND %d" % (first, last)))
            if done >= T1_JOBS:
                break
            time.sleep(2)
        # One more full tick of both enqueuers, so a second pass on either would have shown.
        log("T1: %d/%d jobs have a run; waiting one more enqueuer tick on both instances" % (done, T1_JOBS))
        time.sleep(65)
        rows = self.psql("SELECT job_id, count(*) FROM job_queue WHERE job_id BETWEEN %d AND %d GROUP BY job_id" % (first, last))
        counts = {}
        for line in rows.splitlines():
            job, n = line.split("|")
            counts[int(job)] = int(n)
        dupes = {j: n for j, n in counts.items() if n != 1}
        missing = T1_JOBS - len(counts)
        per_instance = {}
        raced = 0
        for side, container in (("A", "mig132-process-a"), ("B", "mig132-process-b")):
            text = self.logs(container)
            per_instance[side] = sum(int(m) for m in re.findall(r"addJobInQueue --> (\d+) due slot\(s\) enqueued or skipped", text))
            raced += len(re.findall(r"lost its job to another enqueuer", text))
        ok = not dupes and missing == 0
        record("T1", ok, "%d jobs due at once with A and B running: %d with exactly one job_queue row, %d with more (%s), "
               "%d with none; slots taken by A=%d B=%d, index refusals %d"
               % (T1_JOBS, T1_JOBS - len(dupes) - missing, len(dupes), dict(list(dupes.items())[:5]), missing,
                  per_instance["A"], per_instance["B"], raced))

    def login(self, port, username, password):
        status, raw, _ = self.http("POST", port, "/auth.json/login", body={"username": username, "password": password})
        try:
            return status, json.loads(raw).get("message", raw)
        except ValueError:
            return status, raw

    def check_t5(self):
        # A throwaway account made for this and nothing else; every password tried is made up here.
        username = "t5-%s@mig132.invalid" % self.run_id
        self.psql("""INSERT INTO app_user (app_user_id, date_created, full_name, password, status, tenant_id, user_role, username, uuid)
                     VALUES ({id}, now(), 'T5 throwaway', '$2a$10$' || md5(random()::text) || md5(random()::text), 'Active', {t},
                             'TENANT_USER', '{u}', '{uu}') ON CONFLICT (app_user_id) DO UPDATE SET username = EXCLUDED.username;"""
                  .format(id=T5_USER, t=TENANT, u=username, uu=uuid.uuid4()))
        sides = [("A", self.ports["a"]), ("B", self.ports["b"])] * 3
        answers = []
        for side, port in sides[:5]:
            status, message = self.login(port, username, "wrong-" + secrets.token_hex(8))
            answers.append("%s:%s" % (side, message))
        status6, sixth = self.login(self.ports["b"], username, "wrong-" + secrets.token_hex(8))
        status7, seventh = self.login(self.ports["lb"], username, "wrong-" + secrets.token_hex(8))
        wrong = all("Invalid username or password" in a for a in answers)
        locked = "Too many sign-in attempts" in sixth and "Too many sign-in attempts" in seventh
        record("T5", wrong and locked, "five wrong passwords A,B,A,B,A for a throwaway user -> 6th on B: %r; 7th via the "
               "balancer: %r" % (sixth, seventh))

    def check_t4(self):
        admin, user = self.admin, self.user
        grant = "/pageAccess.json/setPageAccess?appUserId=%d&pageKey=reports&allowed=%s"
        s, raw, _ = self.http("PUT", self.ports["a"], grant % (TENANT_USER, "true"), admin)
        if s != 200:
            record("T4", False, "could not grant the page on A: %s %s" % (s, raw[:200]))
            return
        time.sleep(3)
        gated = "/report.json/runs"
        before = self.http("GET", self.ports["b"], gated, user)[0]
        # B now holds "allowed" in its per-JVM copy for the next 15 s. Revoke through A, then ask B.
        t0 = time.time()
        s, raw, _ = self.http("PUT", self.ports["a"], grant % (TENANT_USER, "false"), admin)
        revoked_in = self.poll(lambda: self.http("GET", self.ports["b"], gated, user)[0] == 403, 20)
        time.sleep(1)
        self.http("GET", self.ports["b"], gated, user)
        t1 = time.time()
        self.http("PUT", self.ports["a"], grant % (TENANT_USER, "true"), admin)
        granted_in = self.poll(lambda: self.http("GET", self.ports["b"], gated, user)[0] == 200, 20)
        fast = 2.0 + 1.5  # the 2 s poll, plus a request's worth of slack
        ok = before == 200 and revoked_in is not None and granted_in is not None and revoked_in <= fast and granted_in <= fast
        record("T4", ok, "page 'reports' revoked through A, refused by B after %s; granted through A, open on B after %s "
               "(bound: %.1fs by the shared version, 15 s by the TTL)" % (fmt(revoked_in), fmt(granted_in), fast))

    def poll(self, condition, limit, step=0.1):
        t0 = time.time()
        while time.time() - t0 < limit:
            if condition():
                return time.time() - t0
            time.sleep(step)
        return None

    def check_t6(self):
        # MIG-167 retired lookup_data and, with it, the in-memory lookup copy and its shared cache version. What T6
        # held for lookups now holds for the configuration store that replaced the generic Lookups screen: a value
        # added and then edited through A is what B answers at once. pipeline_config is read from the table on every
        # request -- there is no cache left to go stale -- so the bound is a request's worth of slack.
        pa = self.platform
        key = "MIG132_T6_%s" % re.sub(r"[^A-Z0-9_]", "_", self.run_id.upper())

        def seen_on_b(value):
            s, listed, _ = self.http("GET", self.ports["b"], "/setting.json/pipelineConfig?tenantId=%d" % TENANT, pa)
            return s == 200 and key in listed and value in listed

        timings = []
        entry_id = None
        for step, value in (("add", "v1-%s" % self.run_id), ("edit", "v2-%s" % self.run_id)):
            t0 = time.time()
            if step == "add":
                s, raw, _ = self.http("POST", self.ports["a"], "/setting.json/pipelineConfig", pa,
                                      {"key": key, "kind": "VALUE", "value": value, "description": "MIG-132 T6", "tenantId": TENANT})
                m = re.search(r'"id"\s*:\s*(\d+)', raw)
                entry_id = int(m.group(1)) if m else None
            else:
                s, raw, _ = self.http("PUT", self.ports["a"], "/setting.json/pipelineConfig", pa, {"id": entry_id, "value": value})
            if s != 200 or '"ERROR"' in raw:
                record("T6", False, "%s on A refused: %s %s" % (step, s, raw[:200]))
                return
            timings.append((step, self.poll(lambda: seen_on_b(value), 10)))
        bound = 1.5
        ok = all(x is not None and x <= bound for _, x in timings)
        record("T6", ok, "configuration %s on A seen on B -- " % key + "; ".join(
            "%s: %s" % (step, fmt(x)) for step, x in timings) + " (bound %.1fs; no cache since MIG-167)" % bound)

    def check_t7(self):
        # A run stranded seven hours ago, which the sweep must close exactly once.
        self.psql("""
            INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, created_by,
                                    complete_job, fail_job, skip_job, max_attempts, job_running_status)
            VALUES ({j}, now() AT TIME ZONE 'America/Chicago', 'Manual', 'mig132-t7-stalled', 'Active', 1, {t}, {ta},
                    false, false, false, 1, 'Start') ON CONFLICT DO NOTHING;
            INSERT INTO job_queue (job_queue_id, date_created, job_id, job_send, job_status, job_status_message, run_manual,
                                   skip_manual, start_time, status, attempt)
            VALUES ({j}, (now() AT TIME ZONE 'America/Chicago') - interval '7 hours', {j}, true, 'Start', 'stranded by MIG-132',
                    true, false, (now() AT TIME ZONE 'America/Chicago') - interval '7 hours', 'Active', 1) ON CONFLICT DO NOTHING;
        """.format(j=T7_JOB, t=TENANT, ta=TENANT_ADMIN))
        # Replicas never start at the same instant; restart B so the two are well apart.
        log("T7: restarting B so the replicas' start times differ, then watching three ticks")
        self.compose("restart", "process-b")
        self.wait_healthy(["mig132-process-b"], 300)
        # Every acquisition of the sweep's ShedLock rewrites locked_at and locked_by, and the row outlives
        # the lock, so a change of locked_at is one execution, by whichever instance it names.
        query = "SELECT to_char(locked_at, 'YYYY-MM-DD HH24:MI:SS.MS'), locked_by FROM shedlock WHERE name = 'reconcileStalledRuns'"
        before = self.psql(query).split("|")[0]
        executions = []
        deadline = time.time() + 150
        while time.time() < deadline:
            row = self.psql(query)
            last = executions[-1][0] if executions else before
            if row and row.split("|")[0] != last:
                executions.append(tuple(row.split("|")))
            time.sleep(0.5)
        times = [dt.datetime.strptime(e[0], "%Y-%m-%d %H:%M:%S.%f") for e in executions]
        per_minute = {}
        for t in times:
            per_minute.setdefault(t.strftime("%H:%M"), []).append(t)
        off_tick = [t.strftime("%H:%M:%S") for t in times if t.second >= 20]
        doubled = {m: len(v) for m, v in per_minute.items() if len(v) > 1}
        closed = self.psql("SELECT job_status || '|' || (SELECT count(*) FROM job_audit_logs WHERE job_queue_id = {j} "
                           "AND log_detail LIKE 'Run closed automatically%') FROM job_queue WHERE job_queue_id = {j}".format(j=T7_JOB))
        closed_ok = closed == "Interrupt|1"
        ok = len(times) >= 2 and not doubled and not off_tick and closed_ok
        record("T7", ok, "sweeps seen in 150 s: %s; one per tick: %s; stalled run %s (want Interrupt|1 audit line). "
               "DISPATCH_BUDGET_MS < lockAtMostFor: see the unit gate (DispatchBudgetInsideLockTest)"
               % (["%s by %s" % e for e in executions], "yes" if not doubled and not off_tick else "NO (doubled %s, off tick %s)" % (doubled, off_tick),
                  closed))

    # ------------------------------------------------------------------------------------ phase N

    def phase_notifications(self, checks):
        log("notifications: two replicas")
        for topic in ("platform.job.status.v1", "platform.job.log.v1", "platform.job.lifecycle.v1",
                      "platform.notification.created.v1", "platform.mail.requested.v1"):
            run(["docker", "exec", "mig132-kafka", "kafka-topics", "--bootstrap-server", "localhost:9092", "--create",
                 "--if-not-exists", "--topic", topic, "--partitions", "3", "--replication-factor", "1"], check=False)
        self.compose("up", "-d", "--no-deps", "notifications-a", "notifications-b")
        self.wait_healthy(["mig132-notifications-a", "mig132-notifications-b"], 300)
        owners = self.partition_owners("notifications-service", 90)
        log("notifications: partition owners %s" % owners)
        if "T2" in checks:
            self.check_t2(owners)
        if "T3" in checks:
            self.check_t3(owners)

    def partition_owners(self, group, timeout):
        """(topic, partition) -> "a" or "b", once every partition of the two topics the checks use is
        assigned and both replicas hold at least one of each (the contract topics have 3 partitions)."""
        ips = {}
        for side in ("a", "b"):
            ips[self.inspect("mig132-notifications-" + side, "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}")] = side
        wanted = ("platform.job.status.v1", "platform.notification.created.v1")
        deadline = time.time() + timeout
        owners = {}
        while time.time() < deadline:
            out = run(["docker", "exec", "mig132-kafka", "kafka-consumer-groups", "--bootstrap-server", "localhost:9092",
                       "--describe", "--group", group], check=False).stdout
            owners = {}
            for line in out.splitlines():
                cols = line.split()
                if len(cols) >= 9 and cols[1] in wanted and cols[2].isdigit():
                    owners[(cols[1], int(cols[2]))] = ips.get(cols[-2].lstrip("/"), "?")
            if all(set(v for (t, _), v in owners.items() if t == topic) == {"a", "b"} for topic in wanted):
                time.sleep(3)  # and it held
                return owners
            time.sleep(2)
        return owners

    def key_for(self, owners, topic, side):
        """A record key Kafka's default partitioner sends to a partition that replica `side` consumes."""
        partitions = [p for (t, p) in owners if t == topic]
        for i in range(1000):
            key = "mig132-%d" % i
            if owners.get((topic, murmur2_partition(key.encode(), len(partitions)))) == side:
                return key
        raise RuntimeError("no key lands on %s for %s" % (side, topic))

    def produce(self, topic, event, key):
        run(["docker", "exec", "-i", "mig132-kafka", "kafka-console-producer", "--bootstrap-server", "localhost:9092",
             "--topic", topic, "--property", "parse.key=true", "--property", "key.separator=\t"],
            input_text=key + "\t" + json.dumps(event) + "\n")

    def counter(self, side, name):
        status, raw, _ = self.http("GET", self.ports["notif-" + side], "/actuator/prometheus")
        m = re.search(r"^%s(?:\{[^}]*\})? ([0-9.eE+-]+)$" % re.escape(name), raw, re.M)
        return float(m.group(1)) if m else 0.0

    def event(self, event_type, payload):
        return {"eventId": str(uuid.uuid4()), "eventType": event_type, "occurredAt": dt.datetime.now(dt.timezone.utc).replace(tzinfo=None).isoformat() + "Z",
                "tenantId": TENANT, "producer": "mig132-harness", "traceId": "mig132-" + self.run_id, "payload": payload}

    def check_t2(self, owners):
        # The event is keyed onto a partition only B consumes; the browser is on A (and the reverse would do
        # as well -- what matters is that the replica publishing is not the one the browser is on).
        topic = "platform.job.status.v1"
        consumer, browser = "b", "a"
        admin = self.admin
        far = Stomp(self.ports["notif-" + browser], admin)
        near = Stomp(self.ports["notif-" + consumer], admin)
        try:
            for client in (far, near):
                client.subscribe("/topic/jobs.%d" % TENANT, admin)
            time.sleep(1)
            received_before = self.counter(browser, "ws_broadcast_received_total")
            published_before = self.counter(consumer, "ws_broadcast_published_total")
            marker = "mig132-t2-" + secrets.token_hex(4)
            t0 = time.time()
            self.produce(topic, self.event("JobStatusChanged", {
                "jobId": T1_FIRST_JOB + 1, "jobQueueId": 1, "attempt": 1, "jobRunningStatus": "Running", "message": marker,
                "newTransition": True, "jobName": "mig132-t2"}), self.key_for(owners, topic, consumer))
            got_far = far.wait_for(marker, 10, since=t0)
            got_near = near.wait_for(marker, 5, since=t0)
            published = self.counter(consumer, "ws_broadcast_published_total") - published_before
            received = self.counter(browser, "ws_broadcast_received_total") - received_before
        finally:
            far.close()
            near.close()
        record("T2", got_far is not None and published >= 1 and received >= 1,
               "job event on a partition only notifications-%s consumes: it published %d to the other replicas; "
               "notifications-%s took %d from them and its browser had the event after %s (the consumer's own "
               "browser after %s)" % (consumer, published, browser, received, fmt(got_far), fmt(got_near)))

    def check_t3(self, owners):
        # The user's only session is on A; the notice is keyed onto a partition only B consumes, so B's
        # convertAndSendToUser is the send, and A must deliver it.
        topic = "platform.notification.created.v1"
        consumer, session = "b", "a"
        username = "tenant-user@mig132.invalid"
        user = self.user
        client = Stomp(self.ports["notif-" + session], user)
        try:
            client.subscribe("/user/queue/notifications", user)
            time.sleep(1)
            online = run(["docker", "exec", "mig132-redis", "redis-cli", "SCARD", "ws:online:" + username]).stdout.strip()
            marker = "mig132-t3-" + secrets.token_hex(4)
            t0 = time.time()
            self.produce(topic, self.event("NotificationCreated", {
                "appUserId": TENANT_USER, "type": "USER_ADDED", "severity": "INFO", "title": marker, "body": "MIG-132 T3",
                "link": "/", "recipientUsername": username, "recipientTenantId": TENANT}), self.key_for(owners, topic, consumer))
            got = client.wait_for(marker, 10, since=t0)
            filed_by = [side for side in ("a", "b")
                        if "Filed a USER_ADDED notice" in self.logs("mig132-notifications-" + side, since=iso(t0 - 1))]
        finally:
            client.close()
        record("T3", online not in ("", "0") and got is not None and filed_by == [consumer],
               "the user's only session is on notifications-%s; isOnline (ws:online set) = %s; the notice was filed and "
               "sent (convertAndSendToUser) by notifications-%s; the session had it after %s"
               % (session, online, "+".join(filed_by) or "nobody", fmt(got)))

    # ------------------------------------------------------------------------------------ phase An

    def phase_analytics(self):
        log("analytics: two replicas (first boot builds analytics_db)")
        self.compose("up", "-d", "--no-deps", "analytics-a", "analytics-b")
        self.wait_healthy(["mig132-analytics-a", "mig132-analytics-b"], 300)
        self.compose("stop", "analytics-a", "analytics-b")
        cols = self.psql("SELECT column_name || ':' || is_nullable || ':' || data_type FROM information_schema.columns "
                         "WHERE table_name = 'analytics_query_run' ORDER BY ordinal_position", db="analytics_db")
        self.seed_analytics_history(cols.splitlines())
        log("analytics: both started together; the cleanup fires 60 s after start")
        since = dt.datetime.now(dt.timezone.utc).replace(tzinfo=None).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.compose("start", "analytics-a", "analytics-b")
        self.wait_healthy(["mig132-analytics-a", "mig132-analytics-b"], 300)
        deadline = time.time() + 150
        lines = []
        while time.time() < deadline:
            lines = []
            for side in ("a", "b"):
                for m in re.findall(r"Analytics history cleanup removed (\d+) run rows", self.logs("mig132-analytics-" + side, since=since)):
                    lines.append((side, int(m)))
            if lines:
                break
            time.sleep(2)
        time.sleep(40)  # the other replica's firing, had the lock not held
        lines = []
        for side in ("a", "b"):
            for m in re.findall(r"Analytics history cleanup removed (\d+) run rows", self.logs("mig132-analytics-" + side, since=since)):
                lines.append((side, int(m)))
        left_old = int(self.psql("SELECT count(*) FROM analytics_query_run WHERE date_created < now() - interval '30 days'", db="analytics_db"))
        left_new = int(self.psql("SELECT count(*) FROM analytics_query_run", db="analytics_db"))
        ok = len(lines) == 1 and lines[0][1] == self.t8_old and left_old == 0 and left_new == self.t8_new
        record("T8", ok, "%d expired + %d recent run rows, two replicas started together: deletion counts logged %s; "
               "expired left %d, total left %d" % (self.t8_old, self.t8_new, lines, left_old, left_new))

    def seed_analytics_history(self, columns):
        self.t8_old, self.t8_new = 37, 5
        # Fill every NOT NULL column without a default by type; the two that matter are set on purpose.
        names, values = [], []
        for c in columns:
            name, nullable, kind = c.split(":", 2)
            if name == "date_created":
                names.append(name)
                values.append("{when}")
            elif name == "analytics_query_run_id":
                names.append(name)
                values.append("{base} + g")
            elif nullable == "NO":
                names.append(name)
                values.append(self.filler(name, kind))
        template = "INSERT INTO analytics_query_run (%s) SELECT %s FROM generate_series(1, {n}) g;" % (", ".join(names), ", ".join(values))
        self.psql(template.format(when="now() - interval '60 days'", n=self.t8_old, base=9135000)
                  + template.format(when="now()", n=self.t8_new, base=9136000), db="analytics_db")

    @staticmethod
    def filler(name, kind):
        if name == "tenant_id":
            return str(TENANT)
        if "int" in kind or kind in ("numeric", "double precision", "real"):
            return "0"
        if kind == "boolean":
            return "false"
        if "timestamp" in kind or kind == "date":
            return "now()"
        if kind in ("json", "jsonb"):
            return "'{}'"
        if name == "status":
            return "'Completed'"
        return "'mig132'"

    # ------------------------------------------------------------------------------------ phase Bi

    def phase_billing(self):
        # Every two minutes, not every minute: the close holds its lock for lockAtLeastFor = 1M, so a
        # one-minute tick would find its own previous lock and skip every other time.
        period = int(self.env.get("H_CLOSE_PERIOD", "120"))
        log("billing: two replicas with the month close every %ds" % period)
        since = dt.datetime.now(dt.timezone.utc).replace(tzinfo=None).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.compose("up", "-d", "--no-deps", "meter-stub", "billing-a", "billing-b")
        self.wait_healthy(["mig132-billing-a", "mig132-billing-b"], 300)
        watch = max(150, 2 * period + 30)
        log("billing: watching %ds of close ticks" % watch)
        time.sleep(watch)
        closes = []
        for side in ("a", "b"):
            for line in self.logs("mig132-billing-" + side, since=since).splitlines():
                m = re.search(r"billing: (\d+) draft\(s\) for (\S+), (\d+) already invoiced", line)
                if m:
                    closes.append((line[:23], side, m.group(0)))
        closes.sort()
        times = [dt.datetime.strptime(c[0][:19], "%Y-%m-%dT%H:%M:%S") for c in closes]
        buckets = {}
        for t in times:
            buckets.setdefault(int(t.timestamp()) // period, []).append(t)
        doubled = {k: len(v) for k, v in buckets.items() if len(v) > 1}
        off_tick = [t.strftime("%H:%M:%S") for t in times if int(t.timestamp()) % period >= 20]
        drafts = self.psql("SELECT count(*) FROM invoice WHERE tenant_id = %d" % TENANT, db="billing_db")
        ok = len(times) >= 2 and not doubled and not off_tick
        record("T10", ok, "month close runs seen: %s; one per %ds tick: %s; invoices for the tenant: %s"
               % (["%s %s" % (c[0][11:19], c[1]) for c in closes], period,
                  "yes" if ok else "NO (doubled %s, off tick %s)" % (doubled, off_tick), drafts))

    # ------------------------------------------------------------------------------------ gate

    def test_env(self):
        env = dict(self.env)
        env["NOTIFICATIONS_TEST_DB_URL"] = "jdbc:postgresql://127.0.0.1:15432/etl_job"
        env["NOTIFICATIONS_TEST_DB_USER"] = "harness"
        env["NOTIFICATIONS_TEST_DB_PASSWORD"] = self.secrets["H_PG_PASSWORD"]
        return env

    def full_suite(self):
        """process's whole `mvn -o test`, with the opt-in Postgres suites run against the harness server."""
        self.compose("up", "-d", "postgres")
        self.wait_healthy(["mig132-postgres"], 120)
        log("full suite: mvn -o test (Postgres suites against mig132-postgres)")
        proc = run(["mvn", "-o", "test"], env=self.test_env(), check=False, timeout=1800, cwd=REPO)
        summary = re.findall(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$", proc.stdout, re.M)
        failing = sorted(set(re.findall(r"<<< (?:FAILURE|ERROR)! - in (\S+)", proc.stdout)))
        record("SUITE", proc.returncode == 0, "mvn -o test: run %s, failures %s, errors %s, skipped %s %s"
               % (tuple(summary[-1] if summary else ("?",) * 4) + (", ".join(failing),)))
        return proc.returncode

    def unit_gate(self):
        """The same mechanisms asserted in-process, and the dispatch-lock coupling T7 names. Postgres
        suites run against this harness's server, each in a database of its own."""
        tests = ",".join([
            "ReconcileOncePerTickTest", "DispatchBudgetInsideLockTest", "DispatchTimingTest",
            "PageAccessCacheAcrossInstancesTest",
            "LoginAttemptGuardAcrossInstancesTest", "TokenRevocationAcrossInstancesTest",
            "EnqueuerReplicasPostgresTest", "OneRunInFlightPostgresTest", "DueSchedulerClaimPostgresTest",
            "StalledRunSweepPostgresTest", "SchedulingDisabledTest"])
        env = self.test_env()
        log("unit gate: mvn -o test -Dtest=%s" % tests)
        proc = run(["mvn", "-o", "test", "-Dtest=" + tests, "-DfailIfNoTests=false"], env=env, check=False,
                   timeout=900, cwd=REPO) if not self.args.no_unit else None
        if proc is None:
            record("GATE", None, "skipped (--no-unit)")
            return
        summary = re.findall(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$", proc.stdout, re.M)
        total = summary[-1] if summary else ("?", "?", "?", "?")
        record("GATE", proc.returncode == 0, "cross-instance unit and Postgres suites: run %s, failures %s, errors %s, skipped %s"
               % total)


def iso(epoch):
    return dt.datetime.fromtimestamp(epoch, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def murmur2_partition(data, partitions):
    """Kafka's default partitioner for a keyed record: toPositive(murmur2(key)) % partitions."""
    m, seed = 0x5bd1e995, 0x9747b28c
    mask = 0xFFFFFFFF
    length = len(data)
    h = (seed ^ length) & mask
    for i in range(length // 4):
        k = data[4 * i] | (data[4 * i + 1] << 8) | (data[4 * i + 2] << 16) | (data[4 * i + 3] << 24)
        k = (k * m) & mask
        k ^= k >> 24
        k = (k * m) & mask
        h = (h * m) & mask
        h ^= k
    rest = length % 4
    tail = length & ~3
    if rest == 3:
        h ^= data[tail + 2] << 16
    if rest >= 2:
        h ^= data[tail + 1] << 8
    if rest >= 1:
        h ^= data[tail]
        h = (h * m) & mask
    h ^= h >> 13
    h = (h * m) & mask
    h ^= h >> 15
    return (h & 0x7FFFFFFF) % partitions


def fmt(seconds):
    return "never" if seconds is None else "%.2fs" % seconds


class Stomp:
    """STOMP 1.2 over a raw WebSocket (the SockJS endpoint's /websocket transport), standard library only."""

    def __init__(self, port, token):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        key = base64.b64encode(secrets.token_bytes(16)).decode()
        self.sock.sendall(("GET /api/v1/ws/websocket HTTP/1.1\r\nHost: localhost:%d\r\nUpgrade: websocket\r\n"
                           "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n"
                           "Origin: http://localhost\r\n\r\n" % (port, key)).encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(1)
            if not chunk:
                raise RuntimeError("WebSocket handshake closed")
            head += chunk
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise RuntimeError("WebSocket handshake refused: %r" % head[:200])
        self.buffer = b""
        self.frames = []
        self.send_frame("CONNECT", {"accept-version": "1.2", "host": "localhost", "heart-beat": "0,0",
                                    "Authorization": "Bearer " + token})
        frame = self.next_frame(10)
        if not frame or not frame.startswith("CONNECTED"):
            raise RuntimeError("STOMP CONNECT refused: %r" % (frame or "")[:300])
        self.subs = 0

    def send_ws(self, text):
        payload = text.encode()
        mask = secrets.token_bytes(4)
        header = bytearray([0x81])
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += n.to_bytes(2, "big")
        else:
            header.append(0x80 | 127)
            header += n.to_bytes(8, "big")
        self.sock.sendall(bytes(header) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def send_frame(self, command, headers, body=""):
        self.send_ws(command + "\n" + "".join("%s:%s\n" % kv for kv in headers.items()) + "\n" + body + "\x00")

    def subscribe(self, destination, token):
        self.subs += 1
        self.send_frame("SUBSCRIBE", {"id": "sub-%d" % self.subs, "destination": destination, "Authorization": "Bearer " + token})

    def read_ws(self, timeout):
        self.sock.settimeout(timeout)

        def need(n):
            while len(self.buffer) < n:
                chunk = self.sock.recv(65536)
                if not chunk:
                    raise EOFError()
                self.buffer += chunk

        need(2)
        b1, b2 = self.buffer[0], self.buffer[1]
        n, offset = b2 & 0x7F, 2
        if n == 126:
            need(4)
            n, offset = int.from_bytes(self.buffer[2:4], "big"), 4
        elif n == 127:
            need(10)
            n, offset = int.from_bytes(self.buffer[2:10], "big"), 10
        need(offset + n)
        payload = self.buffer[offset:offset + n]
        self.buffer = self.buffer[offset + n:]
        return b1 & 0x0F, payload.decode(errors="replace")

    def next_frame(self, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                opcode, text = self.read_ws(max(0.1, deadline - time.time()))
            except (socket.timeout, EOFError):
                return None
            if opcode == 8:
                return None
            if opcode == 1 and text.strip("\n\x00"):
                return text
        return None

    def wait_for(self, marker, timeout, since=None):
        """Seconds from `since` (default: now) to the MESSAGE carrying marker, or None."""
        t0 = time.time()
        origin = since or t0
        while time.time() - t0 < timeout:
            frame = self.next_frame(timeout - (time.time() - t0))
            if frame is None:
                return None
            if frame.startswith("MESSAGE") and marker in frame:
                return time.time() - origin
            if frame.startswith("ERROR"):
                raise RuntimeError("STOMP ERROR: %r" % frame[:300])
        return None

    def close(self):
        try:
            self.send_frame("DISCONNECT", {})
            self.sock.close()
        except OSError:
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", help="comma-separated checks, e.g. T1,T4 (default: all; with --mutate, the ones it breaks)")
    parser.add_argument("--image", default=os.environ.get("PROCESS_IMAGE", "process-two-instance:harness"))
    parser.add_argument("--mutate", choices=sorted(MUTATIONS), help="run against a deliberately broken mechanism")
    parser.add_argument("--keep", action="store_true", help="leave the harness running afterwards")
    parser.add_argument("--reuse", action="store_true", help="reuse .run/env instead of new secrets")
    parser.add_argument("--no-unit", action="store_true", help="skip the unit/Postgres gate")
    parser.add_argument("--down", action="store_true", help="only tear the harness down")
    parser.add_argument("--full-suite", action="store_true",
                        help="only process's whole mvn -o test, opt-in Postgres suites included, against the harness Postgres")
    parser.add_argument("--attach", action="store_true",
                        help="run the process checks against a harness left up by --keep (implies --reuse --keep)")
    args = parser.parse_args()
    if args.attach:
        args.reuse = args.keep = True
    if args.only is None:
        args.only = ",".join(MUTATIONS[args.mutate]["expect"] if args.mutate else ALL_CHECKS)
    checks = [c.strip().upper() for c in args.only.split(",") if c.strip()]

    h = Harness(args)
    if args.down:
        h.down()
        return 0
    if args.full_suite:
        h.down()
        try:
            return 1 if h.full_suite() else 0
        finally:
            h.down()
    if args.attach:
        h.seed_people()
        for c in checks:
            if c in ("T1", "T4", "T5", "T6", "T7"):
                getattr(h, "check_" + c.lower())()
        if "T2" in checks or "T3" in checks:
            h.phase_notifications(checks)
        if "T8" in checks:
            h.phase_analytics()
        if "T10" in checks:
            h.phase_billing()
        return 1 if any(s == "FAIL" for s, _ in results.values()) else 0
    if h.mutation:
        log("MUTATION %s: %s -- expect %s to FAIL" % (args.mutate, h.mutation["about"], h.mutation["expect"]))
    h.down()
    try:
        h.up_infra()
        if not args.no_unit and not args.mutate:
            h.unit_gate()
        if any(c in checks for c in ("T1", "T4", "T5", "T6", "T7", "T9", "T2", "T3", "T8", "T10")):
            h.phase_process([c for c in checks if c in ("T1", "T4", "T5", "T6", "T7", "T9")])
            ok, seen = h.lb_evidence
            record("LB", ok, "round-robin with no stickiness: consecutive requests answered by %s" % seen)
        if "T2" in checks or "T3" in checks:
            h.phase_notifications(checks)
            h.compose("stop", "notifications-a", "notifications-b", check=False)
        if "T8" in checks:
            h.phase_analytics()
            h.compose("stop", "analytics-a", "analytics-b", check=False)
        if "T10" in checks:
            h.phase_billing()
    except Exception as err:  # a broken phase is a failed run, with the reason, not a traceback alone
        log("ERROR: %s" % err)
        for c in checks:
            if c not in results:
                record(c, False, "not reached: %s" % str(err).splitlines()[0][:300])
    finally:
        if not args.keep:
            log("tearing the harness down")
            h.down()

    print("\n==== MIG-132 two-instance harness%s ====" % (" (mutation: %s)" % args.mutate if args.mutate else ""))
    for check in ["GATE", "LB"] + ALL_CHECKS:
        if check in results:
            print("%-4s %s  %s" % (check, results[check][0], results[check][1]))
    failed = [c for c, (s, _) in results.items() if s == "FAIL"]
    if h.mutation:
        expected = set(h.mutation["expect"])
        caught = expected & set(failed)
        print("mutation %s: expected red %s, red %s -> %s" % (args.mutate, sorted(expected), sorted(failed),
                                                           "CAUGHT" if caught == expected & set(checks) else "MISSED"))
        return 0 if caught == expected & set(checks) else 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
