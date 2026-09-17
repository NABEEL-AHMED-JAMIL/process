# Tenant Users, Buckets & IAM (LocalStack)

## Date: September 17, 2026
## Environment: local — `process` on :9098, console on :4400, LocalStack on :4566

---

## ⚠️ Read first

- Everything here is **local demo data**. IAM lives in LocalStack (account `000000000000`) and works nowhere else.
- **No secrets in this file** (it is git-tracked). Secret access keys and one-time passwords are fetched or rotated with the commands at the bottom.
- **LocalStack does not enforce IAM** unless its container runs with `ENFORCE_IAM=1` (`job-search/docker-compose.integrated.yml`). Until then every key reaches every bucket; the policies are correct and confine each user in real AWS.
- A one-time password stops working the moment the person signs in and sets their own. Chloe Baker (4413) has already done so.

---

## 🔑 Platform admin

```
Console:   http://localhost:4400/login
Username:  admin@platform.local
Password:  same as the username
```

## ☁️ Platform buckets (the app's own identity)

| Bucket | Holds | Storage connection |
|---|---|---|
| `etl-avatar` | profile pictures at `<userId>/profile/` | ETL Avatars (S3, platform-owned) |
| `etl-config` | Kafka certificates and stores at `kafka-secrets/` | ETL Config (S3, platform-owned) |

Signed with the single `AWS_*` identity in `process/docker-compose.yml` (LocalStack's root key, which bypasses IAM). Platform-wide role for real AWS: `arn:aws:iam::000000000000:role/etl-console-s3-role`, policy `etl-console-s3` on `arn:aws:s3:::*`.

---

## 🏢 CareBridge Health Services (tenant 2901)

### Bucket & IAM

```
Bucket:        s3://carebridge-health-services
IAM user:      etl-carebridge-health-services-user
Access key id: LKIAQAAAAAAAEZC2LI7S        (secret: see "Fetching secrets" below)
Role:          arn:aws:iam::000000000000:role/etl-carebridge-health-services-role
Policy:        arn:aws:iam::000000000000:policy/etl-carebridge-health-services-s3   (this bucket only, no ListAllMyBuckets)
```

Add it in the console: Settings → Storage → New connection → provider **S3**, endpoint `http://host.docker.internal:4566`, region `us-east-1`, bucket and alias as above, the IAM user's key pair. "Discover buckets" will not list it (the key may not enumerate buckets) — type the name.

### Tenant admin

| Id | Name | Email | Password | Picture |
|---|---|---|---|---|
| 4381 | Daniel Carter | `daniel.carter@carebridgehealth.demo` | same as the email | ✅ |

### Users (created by the tenant admin)

| Id | Name | Position | Email | Password | Picture |
|---|---|---|---|---|---|
| 4386 | Alex Morgan | Team Lead | `alex@carebridgehealth.demo` | set by the user | ✅ |
| 4389 | Olivia Bennett | Data Analyst | `olivia.bennett@carebridgehealth.demo` | one-time, in the welcome mail | ✅ |
| 4390 | Liam Foster | Data Engineer | `liam.foster@carebridgehealth.demo` | one-time, in the welcome mail | ✅ |
| 4391 | Ava Patel | Operations Analyst | `ava.patel@carebridgehealth.demo` | one-time, in the welcome mail | ✅ |
| 4392 | Noah Kim | QA Engineer | `noah.kim@carebridgehealth.demo` | one-time, in the welcome mail | ✅ |
| 4393 | Sophia Nguyen | Compliance Officer | `sophia.nguyen@carebridgehealth.demo` | one-time, in the welcome mail | ✅ |

---

## 🏢 NorthStar Health Network (tenant 2902)

### Bucket & IAM

```
Bucket:        s3://northstar-health-network
IAM user:      etl-northstar-health-network-user
Access key id: LKIAQAAAAAAAPFYCO6B5        (secret: see "Fetching secrets" below)
Role:          arn:aws:iam::000000000000:role/etl-northstar-health-network-role
Policy:        arn:aws:iam::000000000000:policy/etl-northstar-health-network-s3   (this bucket only, no ListAllMyBuckets)
```

Add it in the console: Settings → Storage → New connection → provider **S3**, endpoint `http://host.docker.internal:4566`, region `us-east-1`, bucket and alias as above, the IAM user's key pair. "Discover buckets" will not list it (the key may not enumerate buckets) — type the name.

### Tenant admin

| Id | Name | Email | Password | Picture |
|---|---|---|---|---|
| 4382 | Sarah Mitchell | `sarah.mitchell@northstarhealth.demo` | one-time, in the workspace-approval mail (SES outbox) | ✅ |

### Users (created by the tenant admin)

| Id | Name | Position | Email | Password | Picture |
|---|---|---|---|---|---|
| 4394 | Ethan Brooks | Data Engineer | `ethan.brooks@northstarhealth.demo` | one-time, in the welcome mail | ✅ |
| 4395 | Mia Sullivan | Data Analyst | `mia.sullivan@northstarhealth.demo` | one-time, in the welcome mail | ✅ |
| 4396 | Lucas Rivera | Systems Administrator | `lucas.rivera@northstarhealth.demo` | one-time, in the welcome mail | ✅ |
| 4397 | Isabella Chen | Operations Manager | `isabella.chen@northstarhealth.demo` | one-time, in the welcome mail | ✅ |
| 4398 | Mason Reed | Support Specialist | `mason.reed@northstarhealth.demo` | one-time, in the welcome mail | ✅ |

---

## 🏢 HealthCore Community Services (tenant 2903)

### Bucket & IAM

```
Bucket:        s3://healthcore-community-services
IAM user:      etl-healthcore-community-services-user
Access key id: LKIAQAAAAAAAKCLZVYDY        (secret: see "Fetching secrets" below)
Role:          arn:aws:iam::000000000000:role/etl-healthcore-community-services-role
Policy:        arn:aws:iam::000000000000:policy/etl-healthcore-community-services-s3   (this bucket only, no ListAllMyBuckets)
```

Add it in the console: Settings → Storage → New connection → provider **S3**, endpoint `http://host.docker.internal:4566`, region `us-east-1`, bucket and alias as above, the IAM user's key pair. "Discover buckets" will not list it (the key may not enumerate buckets) — type the name.

### Tenant admin

| Id | Name | Email | Password | Picture |
|---|---|---|---|---|
| 4383 | Jessica Williams | `jessica.williams@healthcorecommunity.demo` | one-time, in the workspace-approval mail (SES outbox) | ✅ |

### Users (created by the tenant admin)

| Id | Name | Position | Email | Password | Picture |
|---|---|---|---|---|---|
| 4399 | Charlotte Hayes | Data Analyst | `charlotte.hayes@healthcorecommunity.demo` | one-time, in the welcome mail | ✅ |
| 4400 | James Cooper | Software Engineer | `james.cooper@healthcorecommunity.demo` | one-time, in the welcome mail | ✅ |
| 4401 | Amelia Ortiz | Finance Analyst | `amelia.ortiz@healthcorecommunity.demo` | one-time, in the welcome mail | ✅ |
| 4402 | Benjamin Ward | Data Engineer | `benjamin.ward@healthcorecommunity.demo` | one-time, in the welcome mail | ✅ |
| 4403 | Harper Lee | Product Manager | `harper.lee@healthcorecommunity.demo` | one-time, in the welcome mail | ✅ |

---

## 🏢 EverWell Medical Group (tenant 2904)

### Bucket & IAM

```
Bucket:        s3://everwell-medical-group
IAM user:      etl-everwell-medical-group-user
Access key id: LKIAQAAAAAAAMCQ7V3SZ        (secret: see "Fetching secrets" below)
Role:          arn:aws:iam::000000000000:role/etl-everwell-medical-group-role
Policy:        arn:aws:iam::000000000000:policy/etl-everwell-medical-group-s3   (this bucket only, no ListAllMyBuckets)
```

Add it in the console: Settings → Storage → New connection → provider **S3**, endpoint `http://host.docker.internal:4566`, region `us-east-1`, bucket and alias as above, the IAM user's key pair. "Discover buckets" will not list it (the key may not enumerate buckets) — type the name.

### Tenant admin

| Id | Name | Email | Password | Picture |
|---|---|---|---|---|
| 4384 | Michael Thompson | `michael.thompson@everwellmedical.demo` | one-time, in the workspace-approval mail (SES outbox) | ✅ |

### Users (created by the tenant admin)

| Id | Name | Position | Email | Password | Picture |
|---|---|---|---|---|---|
| 4404 | Elijah Turner | IT Administrator | `elijah.turner@everwellmedical.demo` | one-time, in the welcome mail | ✅ |
| 4405 | Evelyn Morris | Data Analyst | `evelyn.morris@everwellmedical.demo` | one-time, in the welcome mail | ✅ |
| 4406 | Logan Price | Data Engineer | `logan.price@everwellmedical.demo` | one-time, in the welcome mail | ✅ |
| 4407 | Abigail Scott | Operations Analyst | `abigail.scott@everwellmedical.demo` | one-time, in the welcome mail | ✅ |
| 4408 | Jackson Bell | QA Engineer | `jackson.bell@everwellmedical.demo` | one-time, in the welcome mail | ✅ |

---

## 🏢 MedAxis Care Network (tenant 2905)

### Bucket & IAM

```
Bucket:        s3://medaxis-care-network
IAM user:      etl-medaxis-care-network-user
Access key id: LKIAQAAAAAAAGFJXWDDM        (secret: see "Fetching secrets" below)
Role:          arn:aws:iam::000000000000:role/etl-medaxis-care-network-role
Policy:        arn:aws:iam::000000000000:policy/etl-medaxis-care-network-s3   (this bucket only, no ListAllMyBuckets)
```

Add it in the console: Settings → Storage → New connection → provider **S3**, endpoint `http://host.docker.internal:4566`, region `us-east-1`, bucket and alias as above, the IAM user's key pair. "Discover buckets" will not list it (the key may not enumerate buckets) — type the name.

### Tenant admin

| Id | Name | Email | Password | Picture |
|---|---|---|---|---|
| 4385 | Emily Rodriguez | `emily.rodriguez@medaxiscare.demo` | set by the user | ✅ |

### Users (created by the tenant admin)

| Id | Name | Position | Email | Password | Picture |
|---|---|---|---|---|---|
| 4409 | Emma Wright | Data Engineer | `emma.wright@medaxiscare.demo` | one-time, in the welcome mail | ✅ |
| 4410 | Henry Adams | Data Analyst | `henry.adams@medaxiscare.demo` | one-time, in the welcome mail | ✅ |
| 4411 | Grace Murphy | Compliance Officer | `grace.murphy@medaxiscare.demo` | one-time, in the welcome mail | ✅ |
| 4412 | Daniel Hughes | Team Lead | `daniel.hughes@medaxiscare.demo` | one-time, in the welcome mail | ✅ |
| 4413 | Chloe Baker | Support Specialist | `chloe.baker@medaxiscare.demo` | set by the user | — |

---

## 🏢 Default (tenant 2900) — no admin, no users

```
Bucket:        s3://default
IAM user:      etl-default-user
Access key id: LKIAQAAAAAAAIGXSVCXW
Role:          arn:aws:iam::000000000000:role/etl-default-role
Policy:        arn:aws:iam::000000000000:policy/etl-default-s3
```

---

## 🔐 Fetching secrets

**One-time passwords** — every welcome mail went to LocalStack's SES outbox. This prints recipient and password for each:

```bash
curl -s http://localhost:4566/_aws/ses | python3 -c '
import sys,json,re
for m in json.load(sys.stdin)["messages"]:
    raw=m.get("RawData","")
    to=re.search(r"^To: (\S+)", raw, re.M); pw=re.search(r"password</td>.*?<strong>([^<]+)</strong>", raw, re.S)
    if to and pw: print(to.group(1), pw.group(1).strip())'
```

**IAM secret keys** — a secret is shown once at creation and cannot be read back. Rotate instead (the old key keeps working until deleted):

```bash
docker exec localstack-aws awslocal iam create-access-key --user-name etl-carebridge-health-services-user
```

Then, if you like, delete the previous one:

```bash
docker exec localstack-aws awslocal iam list-access-keys --user-name etl-carebridge-health-services-user
docker exec localstack-aws awslocal iam delete-access-key --user-name etl-carebridge-health-services-user --access-key-id <old id>
```

---

## 🛠️ How these were made

**Users** — signed in as each tenant admin, `POST /appUser.json/addUser` with a blank password so the server generated a one-time password and emailed it. **Pictures** — signed in as each user, `POST /storage.json/uploadObject?bucket=etl-avatar&prefix=<id>/profile/`, then `PUT /appUser.json/updateOwnAvatar` with that key (256×256 JPEG from pravatar.cc).

**Bucket, policy, role, IAM user and key** — once per bucket, inside the LocalStack container (or `aws --profile localstack` from the Mac):

```bash
BUCKET=carebridge-health-services
awslocal s3 mb s3://$BUCKET
cat > /tmp/etl-$BUCKET-policy.json <<EOF
{ "Version": "2012-10-17", "Statement": [
  { "Sid": "Bucket",  "Effect": "Allow", "Action": ["s3:ListBucket","s3:GetBucketLocation","s3:ListBucketMultipartUploads"], "Resource": "arn:aws:s3:::$BUCKET" },
  { "Sid": "Objects", "Effect": "Allow", "Action": ["s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload"], "Resource": "arn:aws:s3:::$BUCKET/*" } ] }
EOF
POLICY_ARN=$(awslocal iam create-policy --policy-name etl-$BUCKET-s3 --policy-document file:///tmp/etl-$BUCKET-policy.json --query Policy.Arn --output text)
awslocal iam create-role --role-name etl-$BUCKET-role --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
awslocal iam attach-role-policy --role-name etl-$BUCKET-role --policy-arn $POLICY_ARN
awslocal iam create-user --user-name etl-$BUCKET-user
awslocal iam attach-user-policy --user-name etl-$BUCKET-user --policy-arn $POLICY_ARN
awslocal iam create-access-key --user-name etl-$BUCKET-user
```

**Check what exists**

```bash
docker exec localstack-aws awslocal s3 ls
docker exec localstack-aws awslocal iam list-roles --query 'Roles[?starts_with(RoleName, `etl-`)].RoleName' --output text
docker exec localstack-aws awslocal iam list-users --query 'Users[?starts_with(UserName, `etl-`)].UserName' --output text
```
