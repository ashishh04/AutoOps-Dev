#!/bin/bash
# Logs in to ECR, pulls the images for this release, starts the stack.
set -euo pipefail
cd /opt/autoops
REGISTRY=557690608274.dkr.ecr.ap-south-1.amazonaws.com

aws ecr get-login-password --region ap-south-1 \
  | docker login --username AWS --password-stdin "$REGISTRY"
docker compose pull
docker compose up -d --no-build --remove-orphans
docker compose ps
