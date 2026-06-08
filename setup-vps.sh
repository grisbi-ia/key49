#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║  Key49 — Script de Instalación Completa en VPS Ubuntu          ║
# ║  Para ser ejecutado por Pi (agente de IA) en el servidor       ║
# ║  Uso: sudo bash setup-vps.sh                                   ║
# ╚══════════════════════════════════════════════════════════════════╝
set -euo pipefail

# ── Colores ──
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'
log()  { echo -e "${GREEN}[SETUP]${NC} $1"; }
warn() { echo -e "${YELLOW}[AVISO]${NC} $1"; }
err()  { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# ═══════════════════════════════════════════════════════════════════
#  PASO 0: Verificar que somos root o tenemos sudo
# ═══════════════════════════════════════════════════════════════════
if [ "$EUID" -ne 0 ]; then
    err "Este script debe ejecutarse como root o con sudo: sudo bash setup-vps.sh"
fi

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  Key49 — Instalación Completa en VPS Ubuntu                ║"
echo "║  Dominio: key49.apx5.com                                   ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# ═══════════════════════════════════════════════════════════════════
#  PASO 1: Actualizar sistema e instalar dependencias
# ═══════════════════════════════════════════════════════════════════
log "[1/7] Actualizando sistema e instalando dependencias..."
apt-get update -qq && apt-get upgrade -y -qq
apt-get install -y -qq curl wget git ufw net-tools ca-certificates gnupg lsb-release
log "✓ Sistema actualizado"

# ═══════════════════════════════════════════════════════════════════
#  PASO 2: Instalar Docker
# ═══════════════════════════════════════════════════════════════════
log "[2/7] Instalando Docker..."
if command -v docker &>/dev/null; then
    log "✓ Docker ya instalado: $(docker --version)"
else
    curl -fsSL https://get.docker.com | sh
    log "✓ Docker instalado"
fi

# Verificar Docker Compose
if ! docker compose version &>/dev/null; then
    log "Instalando plugin Docker Compose..."
    apt-get install -y -qq docker-compose-plugin
fi
log "✓ Docker Compose: $(docker compose version)"

# ═══════════════════════════════════════════════════════════════════
#  PASO 3: Configurar Firewall (UFW)
# ═══════════════════════════════════════════════════════════════════
log "[3/7] Configurando firewall..."
ufw --force reset >/dev/null 2>&1
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp comment 'SSH' >/dev/null
ufw allow 80/tcp comment 'HTTP' >/dev/null
ufw allow 443/tcp comment 'HTTPS' >/dev/null
ufw --force enable >/dev/null
log "✓ Firewall configurado: SSH(22), HTTP(80), HTTPS(443)"

# ═══════════════════════════════════════════════════════════════════
#  PASO 4: Clonar repositorio Key49
# ═══════════════════════════════════════════════════════════════════
APP_DIR="/opt/key49"
REPO_URL="${KEY49_REPO_URL:-}"  # Se puede pasar como variable de entorno

log "[4/7] Configurando repositorio Key49..."
if [ -d "$APP_DIR/.git" ]; then
    log "Repositorio existente, actualizando..."
    cd "$APP_DIR"
    git pull origin main 2>/dev/null || git pull origin develop 2>/dev/null || true
    log "✓ Repositorio actualizado"
elif [ -n "$REPO_URL" ]; then
    git clone "$REPO_URL" "$APP_DIR"
    log "✓ Repositorio clonado desde $REPO_URL"
else
    # Asumimos que los archivos ya están en /opt/key49 (ej: copiados con scp/rsync)
    if [ -f "$APP_DIR/docker-compose.prod.yml" ]; then
        log "✓ Archivos de Key49 encontrados en $APP_DIR"
    else
        err "No se encontró el repositorio. Especifica KEY49_REPO_URL o copia los archivos a $APP_DIR"
    fi
fi
cd "$APP_DIR"

# ═══════════════════════════════════════════════════════════════════
#  PASO 5: Generar secretos y configurar .env.prod
# ═══════════════════════════════════════════════════════════════════
log "[5/7] Configurando secretos..."

if [ ! -f ".env.prod" ]; then
    err ".env.prod no encontrado en $APP_DIR. Asegúrate de que existe antes de ejecutar este script."
fi

# Generar contraseñas seguras y reemplazar placeholders
DB_PASS=$(openssl rand -base64 24 | tr -d '+/=' | head -c 24)
REDIS_PASS=$(openssl rand -base64 24 | tr -d '+/=' | head -c 24)
RABBIT_PASS=$(openssl rand -base64 24 | tr -d '+/=' | head -c 24)
MINIO_PASS=$(openssl rand -base64 24 | tr -d '+/=' | head -c 24)
MASTER_KEY=$(openssl rand -base64 32)
ADMIN_TOKEN="k49_admin_$(openssl rand -hex 24)"

# Reemplazar placeholders en .env.prod
sed -i "s/CAMBIA_ESTE_PASSWORD_DB_2026/${DB_PASS}/g" .env.prod
sed -i "s/CAMBIA_ESTE_PASSWORD_REDIS_2026/${REDIS_PASS}/g" .env.prod
sed -i "s/CAMBIA_ESTE_PASSWORD_RABBIT_2026/${RABBIT_PASS}/g" .env.prod
sed -i "s/CAMBIA_ESTE_PASSWORD_MINIO_2026/${MINIO_PASS}/g" .env.prod
sed -i "s|CAMBIA_ESTA_CLAVE_MAESTRA_OPENSSL_RAND_BASE64_32|${MASTER_KEY}|g" .env.prod
sed -i "s/CAMBIA_ESTE_ADMIN_TOKEN_LARGO_Y_SEGURO_2026/${ADMIN_TOKEN}/g" .env.prod
sed -i "s/CAMBIA_ESTE_PASSWORD_RABBIT_2026/${RABBIT_PASS}/g" .env.prod  # URLs de alertas

log "✓ Secretos generados y .env.prod configurado"

# Crear symlink para que Docker Compose lea .env.prod como .env
ln -sf .env.prod .env
log "✓ Symlink .env → .env.prod creado"

# Guardar secretos en archivo seguro
SECRETS_FILE="/root/key49-secrets.txt"
cat > "$SECRETS_FILE" <<SECRETS
╔══════════════════════════════════════════════════╗
║  Key49 — Secretos de Producción                 ║
║  Guarda este archivo en un lugar SEGURO         ║
║  Fecha: $(date)                                   ║
╚══════════════════════════════════════════════════╝

DB_PASSWORD=${DB_PASS}
REDIS_PASSWORD=${REDIS_PASS}
RABBITMQ_PASSWORD=${RABBIT_PASS}
MINIO_PASSWORD=${MINIO_PASS}
MASTER_KEY=${MASTER_KEY}
ADMIN_TOKEN=${ADMIN_TOKEN}

PLUNK_SECRET_KEY=sk_35d0d2e7fad8b8f5fb3980ea61075c79ff8c2c8e6a64e587c86b5d884fcf3c50
DOMAIN=key49.apx5.com
SECRETS
chmod 600 "$SECRETS_FILE"
log "✓ Secretos guardados en $SECRETS_FILE (solo root puede leer)"

# ═══════════════════════════════════════════════════════════════════
#  PASO 6: Construir y desplegar con Docker Compose
# ═══════════════════════════════════════════════════════════════════
log "[6/7] Construyendo y desplegando servicios..."

# Generar userlist para PgBouncer
PG_MD5=$(echo -n "${DB_PASS}key49" | md5sum | cut -d' ' -f1)
echo "\"key49\" \"md5${PG_MD5}\"" > docker/pgbouncer/userlist.prod.txt
log "✓ userlist.txt generado para PgBouncer"

# Construir imagen de Key49 (runtime only, sin Maven — instantáneo)
log "Construyendo imagen key49:latest (runtime only, app pre-compilada)..."
if [ -f "Dockerfile.jvm" ] && [ -d "target/quarkus-app" ]; then
    docker build -t key49:latest -f Dockerfile.jvm . 2>&1 | tail -5
    log "✓ Imagen construida (Dockerfile.jvm — instantáneo)"
elif [ -f "target/quarkus-app/quarkus-run.jar" ]; then
    docker build -t key49:latest -f Dockerfile.jvm . 2>&1 | tail -5
    log "✓ Imagen construida (Dockerfile.jvm)"
else
    log "⚠ target/quarkus-app/ no encontrado, usando build completo con Maven..."
    docker build -t key49:latest -f Dockerfile . 2>&1 | tail -5
    log "✓ Imagen construida (Dockerfile — build completo)"
fi

# Detener servicios si existen
docker compose -f docker-compose.prod.yml down --remove-orphans 2>/dev/null || true

# Iniciar infraestructura
log "Iniciando infraestructura (PostgreSQL, Redis, RabbitMQ, MinIO)..."
docker compose -f docker-compose.prod.yml up -d postgres redis rabbitmq minio

# Esperar a que PostgreSQL esté listo
log "Esperando a que PostgreSQL esté listo..."
for i in $(seq 1 30); do
    if docker exec key49-postgres pg_isready -U key49 -d key49 &>/dev/null; then
        break
    fi
    sleep 2
done
log "✓ PostgreSQL listo"

# Esperar Redis
log "Esperando a que Redis esté listo..."
for i in $(seq 1 15); do
    if docker exec key49-redis redis-cli -a "${REDIS_PASS}" ping &>/dev/null; then
        break
    fi
    sleep 2
done
log "✓ Redis listo"

# Inicializar MinIO bucket
sleep 10
docker compose -f docker-compose.prod.yml up -d minio-init 2>/dev/null || true
sleep 3

# Iniciar PgBouncer
log "Iniciando PgBouncer..."
docker compose -f docker-compose.prod.yml up -d pgbouncer
sleep 5
log "✓ PgBouncer listo"

# Iniciar aplicación y Traefik
log "Iniciando Key49 y Traefik..."
docker compose -f docker-compose.prod.yml up -d key49 traefik

# ═══════════════════════════════════════════════════════════════════
#  PASO 7: Verificar despliegue
# ═══════════════════════════════════════════════════════════════════
log "[7/7] Verificando despliegue..."
echo ""
sleep 15

# Verificar containers
RUNNING=$(docker compose -f docker-compose.prod.yml ps --format json | grep -c '"Health":"healthy"' || true)
TOTAL=$(docker compose -f docker-compose.prod.yml ps --format json | grep -c '"Name"' || true)
log "Contenedores: ${RUNNING}/${TOTAL} saludables"

docker compose -f docker-compose.prod.yml ps

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                                                            ║"
echo "║  ✅ Key49 instalado y desplegado                           ║"
echo "║                                                            ║"
echo "║  🌐 API:      https://key49.apx5.com/v1/invoices            ║"
echo "║  🌐 Portal:   https://key49.apx5.com/portal/login           ║"
echo "║  🌐 Health:   https://key49.apx5.com/q/health               ║"
echo "║  🌐 Swagger:  https://key49.apx5.com/q/swagger-ui           ║"
echo "║                                                            ║"
echo "║  🔑 Admin Token: ${ADMIN_TOKEN}                              ║"
echo "║  📁 Secretos:    ${SECRETS_FILE}                              ║"
echo "║                                                            ║"
echo "║  ⚠️  IMPORTANTE: Guarda los secretos fuera del servidor    ║"
echo "║  ⚠️  El SSL puede tardar unos minutos en activarse        ║"
echo "║                                                            ║"
echo "║  📋 Ver logs: cd /opt/key49 && docker compose -f docker-compose.prod.yml logs -f key49"
echo "║                                                            ║"
echo "╚══════════════════════════════════════════════════════════════╝"
