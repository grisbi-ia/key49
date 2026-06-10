# DEPLOY-VPS.md — Instrucciones para Pi (Agente de IA en el VPS)

## Contexto

Este archivo contiene las instrucciones para que el agente Pi, corriendo en un VPS Ubuntu Server, despliegue **Key49** — plataforma SaaS de facturación electrónica del SRI Ecuador.

**Dominio**: `key49.apx5.com`

---

## Requisitos previos (verificar antes de empezar)

1. **Dominio DNS**: `key49.apx5.com` debe apuntar a la IP pública de este VPS
   ```bash
   dig +short key49.apx5.com
   # Debe devolver la IP de este servidor
   ```

2. **Puertos abiertos**: 80 y 443 deben ser accesibles desde Internet

---

## Cómo funciona (importante para Pi)

**La aplicación ya viene COMPILADA desde la máquina del desarrollador.** El directorio `target/quarkus-app/` contiene el JAR listo para ejecutar. El `Dockerfile.jvm` SOLO copia ese artefacto — **no ejecuta Maven ni compila nada**. El build Docker es instantáneo (segundos, no minutos).

### Flujo completo

**En la máquina del desarrollador:**
```bash
./package-for-vps.sh          # Compila + empaqueta
scp /tmp/key49-vps.tar.gz root@key49.apx5.com:/opt/
```

**En el VPS (Pi ejecuta esto):**
```bash
cd /opt && tar -xzf key49-vps.tar.gz
cd key49 && sudo bash setup-vps.sh
```

El script `setup-vps.sh` hace TODO automáticamente:
1. Instala Docker y dependencias
2. Configura el firewall (UFW)
3. Genera contraseñas seguras aleatorias
4. Construye la imagen Docker (Dockerfile.jvm — **instantáneo**)
5. Despliega los 7 servicios (Traefik, Key49, PostgreSQL, PgBouncer, Redis, RabbitMQ, MinIO)
6. Verifica que todo esté funcionando

### Si necesitas actualizar el código después (deploy de nueva versión)

> **⚠️ CRÍTICO**: El paquete `key49-vps.tar.gz` contiene un `.env.prod` con **placeholders**
> (`CAMBIA_ESTE_PASSWORD_...`). NUNCA sobreescribas el `.env.prod` del VPS — perderías
> todas las contraseñas reales de producción.

#### Paso 1 — En la máquina local (desarrollador)

```bash
# Compilar y empaquetar (perfil prod, sin tests)
cd /home/pvalarezo/auracore-apps/key49
./package-for-vps.sh

# Subir al VPS
scp /tmp/key49-vps.tar.gz root@key49.apx5.com:/opt/
```

#### Paso 2 — En el VPS (extraer SIN pisar secretos)

```bash
# Extraer el tar protegiendo archivos críticos de producción
cd /opt
tar -xzf key49-vps.tar.gz \
    --exclude='.env.prod' \
    --exclude='.env' \
    --exclude='docker/pgbouncer/userlist.prod.txt'
```

**¿Por qué estos `--exclude`?**

| Archivo excluido              | Motivo                                                    |
|-------------------------------|-----------------------------------------------------------|
| `.env.prod`                   | Contiene contraseñas reales de BD, Redis, RabbitMQ, MinIO |
| `.env`                        | Es un symlink a `.env.prod`                               |
| `docker/pgbouncer/userlist.prod.txt` | Contiene hash MD5 de la contraseña real de PostgreSQL |

#### Paso 3 — Reconstruir imagen y reiniciar

```bash
cd /opt/key49

# Reconstruir imagen de la app (~10 segundos, no compila — copia el JAR pre-compilado)
docker build -t key49:latest -f Dockerfile.jvm .

# Reiniciar solo el contenedor de la app (la infraestructura sigue corriendo)
docker compose -f docker-compose.prod.yml up -d key49
```

#### Paso 4 — Verificar que la nueva versión está activa

```bash
# Ver logs de arranque
sleep 10
docker compose -f docker-compose.prod.yml logs --tail=30 key49 | grep -i "started\|version\|error"

# Verificar estado del contenedor
docker compose -f docker-compose.prod.yml ps key49
```

Luego abrir **https://key49.apx5.com/** en el navegador. Al pie de la página
de login aparece la versión desplegada (ej: `v0.31.1`).

#### Rollback (si algo sale mal)

```bash
cd /opt/key49

# Volver al commit/tag anterior y reempaquetar desde local, O reconstruir
# desde el último artefacto bueno si se respaldó target/quarkus-app/
docker build -t key49:last-known-good -f Dockerfile.jvm .
docker tag key49:last-known-good key49:latest
docker compose -f docker-compose.prod.yml up -d key49
```

---

## Después del despliegue

### URLs

| Recurso  | URL                                          |
|----------|----------------------------------------------|
| API REST | `https://key49.apx5.com/v1/invoices`         |
| Portal   | `https://key49.apx5.com/portal/login`        |
| Health   | `https://key49.apx5.com/q/health`            |
| Swagger  | `https://key49.apx5.com/q/swagger-ui`        |

### Verificar la versión desplegada

La página de login (`/portal/login`) muestra la versión al pie del formulario
(ej: `v0.31.1`). Es la forma más rápida de confirmar que un deploy se aplicó
correctamente sin necesidad de SSH.

### Verificar estado

```bash
cd /opt/key49
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs key49 --tail 50
```

### El SSL (HTTPS) puede tardar 1-2 minutos en activarse
Traefik negocia el certificado con Let's Encrypt al primer request. Si no funciona inmediatamente, espera y vuelve a intentar.

### Secretos generados
Los secretos se guardan en `/root/key49-secrets.txt` (solo root puede leer).
**IMPORTANTE**: Copia este archivo a un lugar seguro fuera del servidor.

---

## Troubleshooting

### Si un contenedor no arranca:
```bash
docker compose -f docker-compose.prod.yml logs <nombre-contenedor>
```

### Si el SSL no funciona:
```bash
# Ver logs de Traefik
docker logs key49-traefik

# Verificar que los puertos 80/443 están abiertos
ufw status

# Verificar que el DNS apunta a este servidor
curl -H "Host: key49.apx5.com" http://localhost/.well-known/acme-challenge/test
```

### Si necesitas reiniciar todo:
```bash
cd /opt/key49
docker compose -f docker-compose.prod.yml down
docker compose -f docker-compose.prod.yml up -d
```

### Si necesitas reconstruir Key49 tras cambios de código:
```bash
cd /opt/key49
git pull  # si usas git
docker compose -f docker-compose.prod.yml up -d --build key49
```

---

## Servicios (todos en red interna `key49-net`)

| Servicio  | Puerto (interno) | Acceso externo |
|-----------|------------------|----------------|
| Traefik   | 80, 443          | ✅ (único expuesto) |
| Key49     | 8080             | ❌ (vía Traefik) |
| PostgreSQL| 5432             | ❌ |
| PgBouncer | 6432             | ❌ |
| Redis     | 6379             | ❌ |
| RabbitMQ  | 5672, 15672      | ❌ |
| MinIO     | 9000, 9001       | ❌ |
