#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════╗
# ║  Key49 — Generador de Contraseñas para .env.prod            ║
# ║  Uso: ./docker/generate-secrets.sh                          ║
# ╚══════════════════════════════════════════════════════════════╝
set -euo pipefail

echo "╔══════════════════════════════════════════════════════╗"
echo "║  Key49 — Generando secretos para producción         ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

echo "Contraseñas generadas (copia las que necesites):"
echo "────────────────────────────────────────────────────"
echo ""

echo "KEY49_DB_PASSWORD=$(openssl rand -base64 24 | tr -d '+/=' | head -c 24)"
echo "KEY49_REDIS_PASSWORD=$(openssl rand -base64 24 | tr -d '+/=' | head -c 24)"
echo "KEY49_RABBITMQ_PASSWORD=$(openssl rand -base64 24 | tr -d '+/=' | head -c 24)"
echo "KEY49_MINIO_ROOT_PASSWORD=$(openssl rand -base64 24 | tr -d '+/=' | head -c 24)"
echo "KEY49_MASTER_KEY=$(openssl rand -base64 32)"
echo "KEY49_ADMIN_TOKEN=k49_admin_$(openssl rand -hex 24)"

echo ""
echo "────────────────────────────────────────────────────"
echo ""
echo "Copia estos valores en tu archivo .env.prod"
echo "¡Guarda una copia segura de estos secretos!"
