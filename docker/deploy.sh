#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════╗
# ║  Key49 — Script de Despliegue en Producción                 ║
# ║  Uso: ./docker/deploy.sh                                    ║
# ║  Requisitos: Docker 24+, Docker Compose v2, Git             ║
# ╚══════════════════════════════════════════════════════════════╝
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

# ── Colores ──
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log()  { echo -e "${GREEN}[KEY49]${NC} $1"; }
warn() { echo -e "${YELLOW}[AVISO]${NC} $1"; }
err()  { echo -e "${RED}[ERROR]${NC} $1"; }
info() { echo -e "${BLUE}[INFO]${NC} $1"; }

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  Key49 — Despliegue en Producción                       ║"
echo "║  Dominio: key49.apx5.com                                ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ── 1. Verificar requisitos ──
log "Verificando requisitos..."

if ! command -v docker &>/dev/null; then
    err "Docker no está instalado. Instálalo: https://docs.docker.com/engine/install/ubuntu/"
    exit 1
fi

if ! docker compose version &>/dev/null; then
    err "Docker Compose v2 no encontrado."
    exit 1
fi

if [ ! -f ".env.prod" ]; then
    err ".env.prod no encontrado. Cópialo desde .env.prod.example o créalo."
    exit 1
fi

# Verificar que los passwords no son los defaults
if grep -q "CAMBIA_ESTE" .env.prod; then
    err "¡HAY PASSWORDS SIN CAMBIAR en .env.prod!"
    echo ""
    echo "  Busca 'CAMBIA_ESTE' en .env.prod y reemplaza TODOS los valores."
    echo "  Puedes generar contraseñas seguras con:"
    echo "    openssl rand -base64 32"
    echo ""
    exit 1
fi

log "✓ Requisitos OK"

# ── 2. Cargar variables ──
set -a
source .env.prod
set +a

# ── 3. Generar userlist.txt para PgBouncer ──
log "Generando userlist.txt para PgBouncer..."

# Calcular hash MD5 para PgBouncer (formato: "md5" + md5(password + user))
PG_MD5=$(echo -n "${KEY49_DB_PASSWORD}${KEY49_DB_USER}" | md5sum | cut -d' ' -f1)
echo "\"${KEY49_DB_USER}\" \"md5${PG_MD5}\"" > docker/pgbouncer/userlist.prod.txt
log "✓ userlist.txt generado"

# ── 4. Crear directorios si no existen ──
mkdir -p docker/traefik

# ── 5. Construir imagen de Key49 ──
log "Construyendo imagen Docker de Key49..."
docker build -t key49:latest -f Dockerfile .
log "✓ Imagen key49:latest construida"

# ── 6. Detener servicios anteriores (si existen) ──
log "Deteniendo servicios anteriores..."
docker compose -f docker-compose.prod.yml down --remove-orphans 2>/dev/null || true

# ── 7. Iniciar infraestructura (sin la app) ──
log "Iniciando infraestructura (PostgreSQL, Redis, RabbitMQ, MinIO)..."
docker compose -f docker-compose.prod.yml up -d postgres redis rabbitmq minio

# ── 8. Esperar a que PostgreSQL esté listo ──
log "Esperando a que PostgreSQL esté listo..."
until docker exec key49-postgres pg_isready -U "${KEY49_DB_USER}" -d "${KEY49_DB_NAME}" &>/dev/null; do
    sleep 2
done
log "✓ PostgreSQL listo"

# ── 9. Esperar a que Redis esté listo ──
log "Esperando a que Redis esté listo..."
until docker exec key49-redis redis-cli -a "${KEY49_REDIS_PASSWORD}" ping &>/dev/null; do
    sleep 2
done
log "✓ Redis listo"

# ── 10. Esperar RabbitMQ y MinIO ──
log "Esperando a que RabbitMQ y MinIO estén listos..."
sleep 10

# ── 11. Inicializar bucket MinIO si no existe ──
log "Creando bucket MinIO..."
docker compose -f docker-compose.prod.yml up -d minio-init
log "✓ Bucket MinIO listo"

# ── 12. Iniciar PgBouncer ──
log "Iniciando PgBouncer..."
docker compose -f docker-compose.prod.yml up -d pgbouncer

# ── 13. Iniciar la aplicación Key49 ──
log "Iniciando aplicación Key49..."
docker compose -f docker-compose.prod.yml up -d key49 traefik

# ── 14. Verificar estado ──
echo ""
log "Verificando estado de los servicios..."
sleep 10
docker compose -f docker-compose.prod.yml ps

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  ✅ Despliegue completado                               ║"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║                                                        ║"
echo "║  🌐 API:      https://${KEY49_DOMAIN}/v1/invoices         ║"
echo "║  🌐 Portal:   https://${KEY49_DOMAIN}/portal/login        ║"
echo "║  🌐 Health:   https://${KEY49_DOMAIN}/q/health            ║"
echo "║  🌐 OpenAPI:  https://${KEY49_DOMAIN}/q/swagger-ui        ║"
echo "║                                                        ║"
echo "║  📋 Ver logs: docker compose -f docker-compose.prod.yml logs -f key49"
echo "║  🔄 Reiniciar app: docker compose -f docker-compose.prod.yml restart key49"
echo "║  🛑 Detener todo: docker compose -f docker-compose.prod.yml down"
echo "║                                                        ║"
echo "║  📧 SSL se renovará automáticamente (Let's Encrypt)     ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
info "Puedes monitorear los logs con:"
echo "  docker compose -f docker-compose.prod.yml logs -f key49"
echo ""
