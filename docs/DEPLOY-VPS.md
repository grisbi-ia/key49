# Key49 — Guía de Despliegue en Producción (VPS)

> Guía completa para desplegar, verificar y hacer rollback de Key49 en el VPS de
> producción, **de forma manual o asistida por un agente de IA (Pi)**.
> Incluye los mecanismos de conexión a la base de datos mediante túnel SSH.

---

## Índice

1. [Contexto y topología](#1-contexto-y-topología)
2. [Accesos y prerrequisitos](#2-accesos-y-prerrequisitos)
3. [Método A — Despliegue manual](#3-método-a--despliegue-manual)
4. [Método B — Despliegue asistido por agente (Pi)](#4-método-b--despliegue-asistido-por-agente-pi)
5. [Método C — Primera instalación (bootstrap)](#5-método-c--primera-instalación-bootstrap)
6. [Verificación post-despliegue](#6-verificación-post-despliegue)
7. [Rollback](#7-rollback)
8. [Acceso a la base de datos por SSH](#8-acceso-a-la-base-de-datos-por-ssh)
9. [Operación y diagnóstico](#9-operación-y-diagnóstico)
10. [Troubleshooting](#10-troubleshooting)
11. [Mantenimiento y limpieza](#11-mantenimiento-y-limpieza)
12. [Anexo A — Lecciones del bootstrap inicial](#anexo-a--lecciones-del-bootstrap-inicial-histórico)

---

## 1. Contexto y topología

| Dato | Valor |
| ---- | ----- |
| Dominio | `key49.apx5.com` |
| IP pública | `147.93.147.190` |
| Hostname | `vmi3357488` |
| SO | Ubuntu Server (Docker) |
| SSL | Traefik v3 + Let's Encrypt (automático) |

### Servicios (red interna `key49-net`)

| Servicio | Contenedor | Puerto interno | Expuesto al host |
| -------- | ---------- | -------------- | ---------------- |
| App Key49 (Quarkus) | `key49-app` | 8080 | ❌ (vía Traefik) |
| Traefik (reverse proxy) | `key49-traefik` | 80, 443 | ✅ único público |
| PostgreSQL 16 | `key49-postgres` | 5432 | `127.0.0.1:5432` (solo loopback → túnel SSH) |
| PgBouncer | `key49-pgbouncer` | 6432 | ❌ (interno) |
| Redis 7 | `key49-redis` | 6379 | ❌ |
| RabbitMQ 3.13 | `key49-rabbitmq` | 5672, 15672 | ❌ |
| MinIO | `key49-minio` | 9000, 9001 | ❌ |

### Arquitectura del despliegue

La aplicación **se compila en la máquina del desarrollador** (o del agente
local). El VPS **no ejecuta Maven**: el `Dockerfile.jvm` solo copia el artefacto
`target/quarkus-app/` ya compilado. Por eso el `docker build` en el VPS tarda
**segundos**, no minutos.

```
[ Máquina local ]                         [ VPS producción ]
  ./scripts/package-for-vps.sh                       /opt/key49/
        │  compila vX.Y.Z                        ├── target/quarkus-app/  (jar precompilado)
        ▼                                        ├── Dockerfile.jvm
  /tmp/key49-vps.tar.gz ──scp──► /opt/          ├── docker-compose.prod.yml
                                                 ├── .env.prod          (SECRETO – no tocar)
                                                 └── docker/pgbouncer/userlist.prod.txt (SECRETO)
                                                       │
                                                       ▼
                                          docker build + docker compose up -d key49
                                                       │
                                                       ▼
                                          https://key49.apx5.com  (vX.Y.Z)
```

---

## 2. Accesos y prerrequisitos

### 2.1 Acceso SSH

El despliegue requiere acceso SSH a `root@key49.apx5.com` (puerto **22**).

**Llave dedicada recomendada** (una por operador/agente, no compartir):

```bash
# 1) Generar el par de llaves (en la máquina que desplegará)
ssh-keygen -t ed25519 -N "" -C "pi-agent@key49-deploy" -f ~/.ssh/key49_vps

# 2) Mostrar la llave pública para autorizarla en el VPS
cat ~/.ssh/key49_vps.pub
```

**Autorizar la llave en el VPS** — ejecutar desde una terminal que **ya tenga
acceso**. Es idempotente, hace backup y **nunca borra las llaves existentes**:

```bash
KEY="ssh-ed25519 AAAA... pi-agent@key49-deploy"   # ← pegar la llave pública

ssh root@key49.apx5.com bash -s <<EOF
set -e
mkdir -p ~/.ssh && chmod 700 ~/.ssh
touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys
cp -a ~/.ssh/authorized_keys ~/.ssh/authorized_keys.bak.\$(date +%Y%m%d-%H%M%S)
if grep -qF "pi-agent@key49-deploy" ~/.ssh/authorized_keys; then
  echo "La llave ya estaba presente, no se duplica"
else
  echo "$KEY" >> ~/.ssh/authorized_keys
  echo "Llave agregada correctamente"
fi
echo "Total de llaves autorizadas: \$(grep -c . ~/.ssh/authorized_keys)"
EOF
```

**Verificar el acceso** (siempre con `-o IdentitiesOnly=yes` para forzar la llave correcta):

```bash
ssh -i ~/.ssh/key49_vps -o IdentitiesOnly=yes root@key49.apx5.com 'echo SSH_OK; hostname'
# Esperado: SSH_OK / vmi3357488
```

### 2.2 Archivos sensibles — REGLAS DE ORO

> ⚠️ **NUNCA sobreescribir estos archivos en el VPS.** Contienen las contraseñas
> reales de producción. El paquete `.tar.gz` trae versiones con *placeholders*.

| Archivo en el VPS | Contenido | Cómo protegerlo |
| ----------------- | --------- | --------------- |
| `/opt/key49/.env.prod` | Contraseñas de PostgreSQL, Redis, RabbitMQ, MinIO, master key, admin token | Excluir al extraer el tar |
| `/opt/key49/.env` | Copia/symlink de `.env.prod` (lo lee Docker Compose) | Excluir al extraer |
| `/opt/key49/docker/pgbouncer/userlist.prod.txt` | Hash MD5 de la contraseña de PostgreSQL | Excluir al extraer |
| `/root/key49-secrets.txt` | Copia de respaldo de todos los secretos (solo root) | Backup externo, no tocar |

### 2.3 Herramientas necesarias

| Máquina | Herramienta |
| ------- | ----------- |
| Local (build) | JDK 25, Maven, bash, `scp` |
| VPS | Docker + `docker compose` (ya instalados por `scripts/setup-vps.sh`) |
| Local (admin BD) | `psql` y/o cliente gráfico (DBeaver, pgAdmin) |

### 2.4 Scripts operativos

Todos viven en [`scripts/`](../scripts/):

| Script | Para qué | Dónde se ejecuta |
| ------ | -------- | ---------------- |
| `scripts/package-for-vps.sh` | Compila y empaqueta `target/quarkus-app/` en `/tmp/key49-vps.tar.gz` | Local |
| `scripts/setup-vps.sh` | Bootstrap completo (Docker, firewall, secretos, despliegue) | VPS (solo primera vez) |
| `scripts/generate-secrets.sh` | Genera contraseñas seguras para `.env.prod` | Local |
| `scripts/test-curls.sh` | Pruebas manuales de emisión en desarrollo | Local |
| `scripts/test-curls-prod.sh` | Pruebas manuales de emisión contra producción | Local |

> Los scripts de prueba leen la API key de `KEY49_API_KEY` (obligatoria) y la
> URL base de `KEY49_BASE_URL` (opcional, con default). Tabla completa en el
> [`README`](../README.md#scripts).

---

## 3. Método A — Despliegue manual

Flujo para un operador humano. Tiempo total típico: **3–5 minutos**.

> Reemplazar `<TS>` por un timestamp, p. ej. `$(date +%Y%m%d-%H%M%S)`.

### Paso 1 — Compilar y empaquetar (local)

```bash
cd /home/pvalarezo/auracore-apps/key49
git log --oneline -1          # confirmar el commit
grep -m1 "<version>" pom.xml  # confirmar la versión
./scripts/package-for-vps.sh
```

Genera `/tmp/key49-vps.tar.gz` (~88 MB) e imprime el MD5. Guardarlo:

```bash
md5sum /tmp/key49-vps.tar.gz
```

### Paso 2 — Preparar red de seguridad (VPS)

```bash
TS=$(date +%Y%m%d-%H%M%S)

ssh -i ~/.ssh/key49_vps root@key49.apx5.com "
  set -e
  TS=$TS
  # 1) Tag de rollback de la imagen que está corriendo AHORA
  docker tag key49:latest key49:rollback-\$TS
  # 2) Backup de secretos y configuración
  cp -a /opt/key49/.env.prod /root/key49-env.prod.backup-\$TS
  cp -a /opt/key49/docker/pgbouncer/userlist.prod.txt /root/userlist.prod.backup-\$TS
  cp -a /opt/key49/docker-compose.prod.yml /root/backup-docker-compose.prod.yml-\$TS
  cp -a /opt/key49/Dockerfile.jvm /root/backup-Dockerfile.jvm-\$TS
  echo 'Red de seguridad lista: key49:rollback-'\$TS
"
```

### Paso 3 — Subir el paquete (local → VPS)

```bash
scp -i ~/.ssh/key49_vps /tmp/key49-vps.tar.gz \
    root@key49.apx5.com:/opt/key49-vps.tar.gz

# Verificar integridad
ssh -i ~/.ssh/key49_vps root@key49.apx5.com 'md5sum /opt/key49-vps.tar.gz'
# Debe coincidir con el MD5 local
```

### Paso 4 — Extraer SIN pisar secretos (VPS)

> El tar tiene el prefijo `key49/`. Antes de extraer, se retira el `target`
> viejo para evitar que quede mezclado el jar anterior con el nuevo.

```bash
ssh -i ~/.ssh/key49_vps root@key49.apx5.com "
  set -e
  TS=\$(date +%Y%m%d-%H%M%S)
  cd /opt/key49
  # Guardar md5 ANTES
  md5sum .env.prod
  # Retirar target viejo
  mv target target.\$(grep -m1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<.*/\1/')-\$TS 2>/dev/null || true
  # Extraer excluyendo secretos
  cd /opt
  tar -xzf /opt/key49-vps.tar.gz \
      --exclude='key49/.env.prod' \
      --exclude='key49/.env' \
      --exclude='key49/docker/pgbouncer/userlist.prod.txt'
  # Verificar md5 DESPUÉS (debe ser idéntico)
  md5sum /opt/key49/.env.prod
  grep -m1 '<version>' /opt/key49/pom.xml
  ls /opt/key49/target/quarkus-app/app/
"
```

**Criterio de éxito:** el MD5 de `.env.prod` no cambió, el `pom.xml` muestra la
versión nueva y el jar `key49-X.Y.Z.jar` está presente.

### Paso 5 — Reconstruir la imagen (VPS)

```bash
ssh -i ~/.ssh/key49_vps root@key49.apx5.com \
  'cd /opt/key49 && docker build -t key49:latest -f Dockerfile.jvm .'
```

### Paso 6 — Recrear solo el contenedor de la app (VPS)

```bash
ssh -i ~/.ssh/key49_vps root@key49.apx5.com \
  'cd /opt/key49 && docker compose -f docker-compose.prod.yml up -d key49'
```

La infraestructura (PostgreSQL, Redis, RabbitMQ, MinIO, PgBouncer, Traefik)
**sigue corriendo**; solo se recrea `key49-app`.

### Paso 7 — Verificar

Ir a [Verificación post-despliegue](#6-verificación-post-despliegue).

---

## 4. Método B — Despliegue asistido por agente (Pi)

Un agente (Pi, Claude, etc.) puede ejecutar el **mismo procedimiento** del
Método A de forma autónoma, siempre que se le den los accesos y las reglas de
seguridad adecuadas.

### 4.1 Preparación (una sola vez)

1. Autorizar la llave SSH del agente (§2.1).
2. Darle acceso al repositorio y a `scripts/package-for-vps.sh`.
3. Confirmar que el agente conoce las **REGLAS DE ORO** (§2.2).

### 4.2 Instrucciones sugeridas para el agente

> Copiar/pegar y ajustar la versión objetivo.

```
Desplegá Key49 en producción siguiendo DEPLOY-VPS.md, Método A.
Referencia: commit <SHA> / versión vX.Y.Z.

Reglas obligatorias:
- Llave SSH: ~/.ssh/key49_vps    (usar -o IdentitiesOnly=yes)
- NUNCA sobreescribir /opt/key49/.env.prod, /opt/key49/.env ni
  /opt/key49/docker/pgbouncer/userlist.prod.txt al extraer el tar.
- Antes de tocar prod: crear tag de rollback (key49:rollback-<TS>) y
  backup de .env.prod / userlist.prod.txt / docker-compose.prod.yml.
- Verificar el MD5 del tar subido y el MD5 de .env.prod antes/después.
- Recrear SOLO el servicio key49 (docker compose up -d key49).
- Al terminar: reportar versión visible en /portal/login, estado de
  /q/health (11 checks) y logs sin errores.
- Si algo falla: ejecutar el rollback y reportar, sin improvisar.
```

### 4.3 Checklist obligatoria del agente

Antes de declarar el despliegue exitoso, el agente **debe** verificar y reportar:

| # | Verificación | Comando |
| - | ------------ | ------- |
| 1 | MD5 del tar coincide | `md5sum` local vs VPS |
| 2 | `.env.prod` intacto | `md5sum` antes/después |
| 3 | Versión en `pom.xml` del VPS | `grep -m1 '<version>' /opt/key49/pom.xml` |
| 4 | Contenedor healthy | `docker ps --filter name=key49-app` |
| 5 | Versión visible al usuario | `curl -s https://key49.apx5.com/portal/login \| grep -o 'v[0-9.]*'` |
| 6 | Health general | `curl -s https://key49.apx5.com/q/health/ready` → 200 |
| 7 | Health detallado | `/q/health` → 11 checks `UP` |
| 8 | Sin errores en logs | `docker logs key49-app --tail=200 \| grep -iE 'error\|exception'` |

### 4.4 Guardrails (lo que el agente NO debe hacer)

- ❌ No ejecutar `scripts/setup-vps.sh` en un servidor que ya está en producción
  (regenera secretos y rompería la BD). Solo para bootstrap (§5).
- ❌ No usar `docker compose down` ni reiniciar la infraestructura.
- ❌ No tocar volúmenes, ni borrar `.env.prod` / `userlist.prod.txt`.
- ❌ No exponer el puerto 5432 a Internet (debe quedar en `127.0.0.1`).
- ❌ No improvisar cambios de esquema/BD durante un deploy de aplicación.

---

## 5. Método C — Primera instalación (bootstrap)

> Solo para un VPS **nuevo/vacío**. Si ya está en producción, ir al Método A.

`scripts/setup-vps.sh` hace todo el bootstrap:

1. Actualiza el sistema e instala dependencias.
2. Instala Docker + Docker Compose.
3. Configura el firewall (UFW: solo 22, 80, 443).
4. Clona el repositorio.
5. **Genera secretos aleatorios** y los guarda en `/root/key49-secrets.txt`.
6. Construye la imagen y levanta los 7 servicios.

```bash
cd /opt && tar -xzf key49-vps.tar.gz
cd /opt/key49
sudo bash scripts/setup-vps.sh
```

> ⚠️ Tras el bootstrap, **respaldar `/root/key49-secrets.txt` fuera del servidor**.

---

## 6. Verificación post-despliegue

### 6.1 Desde fuera (sin SSH)

```bash
# Health general
curl -s -o /dev/null -w "%{http_code}\n" https://key49.apx5.com/q/health/ready
# → 200

# Versión desplegada (visible en el login)
curl -s https://key49.apx5.com/portal/login | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' | sort -u

# Redirección raíz
curl -s -o /dev/null -w "%{http_code} -> %{redirect_url}\n" https://key49.apx5.com/
# → 303 -> /portal/login
```

### 6.2 Health detallado (11 checks)

```bash
curl -s https://key49.apx5.com/q/health
```

Checks esperados: SRI Recepción, SRI Autorización, SmallRye Messaging
(liveness/readiness/startup), MinIO bucket, Database connections, RabbitMQ queue
depth, Redis connection, Datasource pool, Certificate expiration.

### 6.3 Desde el VPS (SSH)

```bash
ssh -i ~/.ssh/key49_vps root@key49.apx5.com '
  docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}" &&
  docker logs key49-app --tail=40 | grep -iE "started|error|exception" &&
  docker images | grep key49
'
```

---

## 7. Rollback

### 7.1 Rollback rápido (recomendado) — volver a la imagen anterior

```bash
ssh -i ~/.ssh/key49_vps root@key49.apx5.com '
  docker tag key49:rollback-<TS> key49:latest &&
  cd /opt/key49 &&
  docker compose -f docker-compose.prod.yml up -d key49
'
```

### 7.2 Rollback del código (reconstruir desde un tag anterior)

```bash
# En local
cd /home/pvalarezo/auracore-apps/key49
git checkout v0.31.11          # o el tag/commit estable
./scripts/package-for-vps.sh
# repetir Método A, pasos 3–6
git checkout main
```

### 7.3 Rollback de configuración

```bash
ssh -i ~/.ssh/key49_vps root@key49.apx5.com '
  cp -a /root/backup-docker-compose.prod.yml-<TS> /opt/key49/docker-compose.prod.yml &&
  cd /opt/key49 && docker compose -f docker-compose.prod.yml up -d key49
'
```

---

## 8. Acceso a la base de datos por SSH

### 8.1 El porqué

PostgreSQL en producción **no está expuesto a Internet**. El
`docker-compose.prod.yml` lo publica únicamente en loopback del VPS:

```yaml
ports:
  - "127.0.0.1:5432:5432"
```

Por lo tanto, para conectarse desde tu equipo hay que **tunelizar por SSH**.

| Parámetro | Valor |
| --------- | ----- |
| Host (desde el VPS) | `127.0.0.1:5432` |
| Base de datos | `key49` |
| Usuario | `key49` |
| Contraseña | en `/opt/key49/.env.prod` (`KEY49_DB_PASSWORD`) |

> **PgBouncer (6432)** es el pooler que usa la aplicación en modo transacción.
> **No** es adecuado para sesiones administrativas de `psql`; para administración
> usar **PostgreSQL directo** a través del túnel.

### 8.2 Túnel SSH (manual)

Elegir un puerto local libre (ej. `15432` para no chocar con el PostgreSQL de
desarrollo, que suele usar `5433`):

```bash
# Tunel en primer plano (dejar la terminal abierta)
ssh -i ~/.ssh/key49_vps -o IdentitiesOnly=yes \
    -L 15432:127.0.0.1:5432 root@key49.apx5.com -N

# En OTRA terminal:
psql -h 127.0.0.1 -p 15432 -U key49 -d key49
```

Obtener la contraseña (sin imprimirla en el historial):

```bash
export PGPASSWORD=$(ssh -i ~/.ssh/key49_vps root@key49.apx5.com \
  "grep -m1 '^KEY49_DB_PASSWORD=' /opt/key49/.env.prod | cut -d= -f2-")
psql -h 127.0.0.1 -p 15432 -U key49 -d key49 -c "SELECT current_database(), current_user;"
unset PGPASSWORD
```

**Túnel en background** (útil para sesiones largas o GUIs):

```bash
ssh -f -N -i ~/.ssh/key49_vps -o IdentitiesOnly=yes -o ExitOnForwardFailure=yes \
    -L 15432:127.0.0.1:5432 root@key49.apx5.com

# Cerrar el túnel cuando termines
pkill -f "15432:127.0.0.1:5432"
```

### 8.3 Cliente gráfico (DBeaver / pgAdmin)

Configurar una conexión con **túnel SSH**:

| Campo | Valor |
| ----- | ----- |
| Host | `127.0.0.1` |
| Port | `15432` (o el puerto local elegido) |
| Database | `key49` |
| Username | `key49` |
| Password | `KEY49_DB_PASSWORD` de `.env.prod` |

En DBeaver → pestaña **SSH**: Host `key49.apx5.com`, Port `22`, User `root`,
método **Public Key**, archivo privado `~/.ssh/key49_vps`.

### 8.4 Via agente (Pi)

El agente puede abrir el túnel y ejecutar consultas de solo lectura:

```bash
export PGPASSWORD=$(ssh -i ~/.ssh/key49_vps root@key49.apx5.com \
  "grep -m1 '^KEY49_DB_PASSWORD=' /opt/key49/.env.prod | cut -d= -f2-")
ssh -f -N -i ~/.ssh/key49_vps -o ExitOnForwardFailure=yes \
    -L 15432:127.0.0.1:5432 root@key49.apx5.com
psql -h 127.0.0.1 -p 15432 -U key49 -d key49 -c "SELECT tenant_id, ruc, schema_name, status FROM tenants;"
pkill -f "15432:127.0.0.1:5432"
```

> Para operaciones de administración detalladas (crear tenants, particiones,
> backups), ver **`docs/DB-ADMIN.md`**.

---

## 9. Operación y diagnóstico

```bash
ssh -i ~/.ssh/key49_vps root@key49.apx5.com '
  cd /opt/key49
  docker compose -f docker-compose.prod.yml ps          # estado de servicios
  docker logs key49-app --tail 100 -f                   # logs en vivo
  docker stats --no-stream                              # CPU/RAM
  df -h /                                               # disco
'
```

| Recurso | URL |
| ------- | --- |
| API REST | `https://key49.apx5.com/v1/...` |
| Portal | `https://key49.apx5.com/portal/login` |
| Health | `https://key49.apx5.com/q/health` |
| Swagger | `https://key49.apx5.com/q/swagger-ui` |

> **Nota:** el contenedor `key49-traefik` puede figurar como `unhealthy` porque su
> healthcheck usa una herramienta no incluida en la imagen. **No afecta el
> servicio**: verificar HTTPS con `curl -I https://key49.apx5.com`.

---

## 10. Troubleshooting

| Síntoma | Causa probable | Solución |
| ------- | -------------- | -------- |
| La versión del login no cambia | Contenedor no recreado | `docker compose -f docker-compose.prod.yml up -d key49` |
| App no arranca | Error de config/BD | `docker logs key49-app --tail=100` |
| Secretos perdidos | Se extrajo el tar sin `--exclude` | Restaurar desde `/root/key49-env.prod.backup-*` |
| SSL no responde | Traefik aún negocia cert | Esperar 1–2 min; `docker logs key49-traefik` |
| `psql` no conecta por túnel | Túnel cerrado / puerto ocupado | Reabrir túnel; usar otro puerto local |
| Disco lleno | Tars/imágenes viejas | Ver §11 |

---

## 11. Mantenimiento y limpieza

```bash
ssh -i ~/.ssh/key49_vps root@key49.apx5.com '
  # Paquete de deploy usado
  rm -f /opt/key49-vps-*.tar.gz
  # Target de versiones anteriores (conservar solo si se necesita reconstruir)
  rm -rf /opt/key49/target.v*
  # Imágenes de rollback antiguas (conservar al menos la última)
  docker images --filter "reference=key49:rollback-*"
  # Limpieza general de imágenes no usadas
  docker image prune -f
'
```

> Conservar **al menos** la última imagen `key49:rollback-<TS>` hasta confirmar
> estabilidad de la versión desplegada.

---

## Anexo A — Lecciones del bootstrap inicial (histórico)

Resumen de los problemas encontrados durante el **primer despliegue** (jun-2026) y
sus soluciones. Ya están aplicadas en la configuración actual; se conservan como
referencia en caso de **reprovisionar el servidor desde cero**.

| # | Problema | Causa | Solución aplicada |
| - | -------- | ----- | ----------------- |
| 1 | App no arranca: `OpenTelemetry exporter set to 'otlp' but upstream dependencies not found` | `application.properties` pedía exporter `otlp` sin la dependencia | Exporter por defecto `cdi` en `%prod` |
| 2 | `docker compose` no resuelve `${VAR}` de `.env.prod` | Compose lee `.env` por defecto | Symlink `.env -> .env.prod` (lo crea `scripts/setup-vps.sh`) |
| 3 | RabbitMQ 3.13 en loop de reinicio | Variables deprecadas `RABBITMQ_VM_MEMORY_HIGH_WATERMARK` / `RABBITMQ_DISK_FREE_LIMIT` tratadas como error fatal | Eliminadas del compose (3.13 usa defaults) |
| 4 | Validación SMTP falla con host vacío | `KEY49_SMTP_HOST=` (string vacío) no usa el default | Usar `localhost` o `QUARKUS_MAILER_MOCK=true` en el bootstrap |
| 5 | PgBouncer: `cannot do SCRAM authentication: wrong password type` | PostgreSQL 16 usa SCRAM; PgBouncer configurado con MD5 | Forzar `password_encryption=md5` en Postgres, ajustar `pg_hba.conf` y regenerar `userlist.prod.txt` con hash MD5 |
| 6 | Health `MinIO bucket: DOWN` | El servicio `minio-init` (crea el bucket) no se ejecutó | `docker compose up -d minio-init` |
| 7 | Traefik no descubre contenedores | Traefik v3.3 usa Docker API 1.24; Docker 29 exige ≥ 1.40 | Actualizar imagen (fijada a `traefik:v3.7`) |
| 8 | Let's Encrypt: `unable to parse email address` | Traefik no expande `${KEY49_ACME_EMAIL}` en su config estática | Email ACME literal en `docker/traefik/traefik.yml` |
| 9 | `GET /` devolvía 404 JSON | No existía endpoint `/` y `ApiKeyAuthFilter` no lo tenía como ruta pública | `RootRedirectResource` (303 → `/portal/login`) + `/` en `isPublicPath()` |

> **Nota sobre caché de capas Docker:** durante el bootstrap, un cambio en
> `src/main/java` no aparecía en la imagen porque `docker build` reutilizaba
> capas cacheadas de `COPY src/`. Por eso el flujo de producción actual **no
> compila en el VPS**: usa `Dockerfile.jvm`, que copia el artefacto
> `target/quarkus-app/` ya compilado en local (sin caché de fuentes).
