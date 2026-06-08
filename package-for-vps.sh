#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════╗
# ║  Key49 — Build local + empaquetar para VPS                 ║
# ║  Compila en tu máquina, empaqueta solo lo necesario        ║
# ║  Uso: ./package-for-vps.sh                                 ║
# ╚══════════════════════════════════════════════════════════════╝
set -euo pipefail

GREEN='\033[0;32m'
NC='\033[0m'
log() { echo -e "${GREEN}[PACKAGE]${NC} $1"; }

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║  Key49 — Build + Empaquetado para VPS               ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

# ── 1. Compilar ──
log "[1/3] Compilando proyecto (perfil prod, sin tests)..."
mvn clean package -DskipTests -Dquarkus.profile=prod -q
log "✓ Compilación exitosa"

# Verificar que el artefacto existe
if [ ! -f "target/quarkus-app/quarkus-run.jar" ]; then
    echo "ERROR: No se encontró target/quarkus-app/quarkus-run.jar"
    exit 1
fi

SIZE=$(du -sh target/quarkus-app | cut -f1)
log "  Artefacto: target/quarkus-app/ ($SIZE)"

# ── 2. Empaquetar ──
log "[2/3] Empaquetando para envío al VPS..."
PACKAGE="/tmp/key49-vps.tar.gz"

tar -czf "$PACKAGE" \
    --exclude='.git' \
    --exclude='target/quarkus-app' \
    --exclude='target/*.jar' \
    --exclude='target/classes' \
    --exclude='target/generated*' \
    --exclude='target/maven-*' \
    --exclude='node_modules' \
    --exclude='*.log' \
    --exclude='.vscode' \
    --exclude='.idea' \
    --exclude='.github' \
    --exclude='.claude' \
    --exclude='api-tests.http' \
    --exclude='test-curls.sh' \
    --exclude='CHANGELOG.md' \
    --exclude='key49.log' \
    src/main \
    target/quarkus-app \
    Dockerfile.jvm \
    docker-compose.prod.yml \
    docker-compose.yml \
    Dockerfile \
    docker/ \
    db/ \
    docs/ \
    .env \
    .env.prod \
    .dockerignore \
    .gitignore \
    pom.xml \
    setup-vps.sh \
    DEPLOY-VPS.md \
    README.md

SIZE=$(ls -lh "$PACKAGE" | awk '{print $5}')
log "✓ Paquete: $PACKAGE ($SIZE)"

# ── 3. Mostrar comando SCP ──
echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║  📤 Subir al VPS:                                   ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║                                                      ║"
echo "║  scp $PACKAGE root@key49.apx5.com:/opt/              ║"
echo "║                                                      ║"
echo "║  Luego en el VPS:                                    ║"
echo "║  cd /opt && tar -xzf key49-vps.tar.gz                ║"
echo "║  cd key49 && sudo bash setup-vps.sh                  ║"
echo "║                                                      ║"
echo "║  ⚡ El build en VPS es INSTANTÁNEO porque            ║"
echo "║     la app ya viene compilada (Dockerfile.jvm)       ║"
echo "║                                                      ║"
echo "╚══════════════════════════════════════════════════════╝"
