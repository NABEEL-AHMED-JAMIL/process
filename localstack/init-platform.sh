#!/bin/bash
# What process expects to find on AWS, recreated every time LocalStack starts.
#
# LocalStack keeps nothing across a restart unless persistence is configured. Without this script a
# restart left process with no buckets and no verified sender: avatars, billing PDFs and Kafka key material failed to store, and
# every email was refused. Names mirror application-dev.properties -- change them together.
set -euo pipefail

for bucket in etl-avatar etl-config; do   # app.avatar.bucket, app.config.bucket
  awslocal s3api head-bucket --bucket "$bucket" 2>/dev/null || awslocal s3 mb "s3://$bucket"
done

# app.mail.from. SES refuses to send from an address it has not verified.
awslocal ses verify-email-identity --email-address no-reply@etl-console.local

echo "etl-console platform resources ready"
