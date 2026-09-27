#!/bin/bash
# Writes /opt/autoops/.env from Secrets Manager, then adds the deploy values.
set -euo pipefail
cd /opt/autoops
REGION=ap-south-1
ACCOUNT_ID=557690608274

aws secretsmanager get-secret-value --secret-id autoops/dev/env --region "$REGION" \
  --query SecretString --output text > .env
printf '\nIMAGE_TAG=%s\nECR_REGISTRY=%s.dkr.ecr.%s.amazonaws.com\n' \
  "$(cat IMAGE_TAG)" "$ACCOUNT_ID" "$REGION" >> .env
chmod 600 .env
echo "fetch_env: .env written ($(wc -l < .env) lines)"
