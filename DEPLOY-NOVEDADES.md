# Key49 — Novedades del Despliegue en VPS

**Fecha:** 2026-06-08  
**Servidor:** Ubuntu 24.04 (Noble)  
**Dominio:** `key49.apx5.com`  
**IP:** `147.93.147.190`  
**Docker:** 29.5.3 · **Docker Compose:** v5.1.4  

---

## Resumen

El script `setup-vps.sh` se ejecutó correctamente en sus primeros 5 pasos, pero falló en el paso 6 (construcción de imagen Docker y despliegue de servicios). Se identificaron y corrigieron **9 problemas** — 8 durante el despliegue inicial y 1 detectado en la verificación post-despliegue (acceso desde navegador).

---

## Problemas encontrados y soluciones

### 1. Dependencia faltante de OpenTelemetry OTLP Exporter

**Error:**
```
OpenTelemetry exporter set to 'otlp' but upstream dependencies not found.
The Quarkus default exporters are already OTLP protocol compliant.
```

**Causa:** `application.properties` configuraba `quarkus.otel.traces.exporter=otlp` en producción, pero `pom.xml` no incluía la dependencia `quarkus-opentelemetry-exporter-otlp`. En Quarkus 3.34.2, la extensión nativa `cdi` ya es compatible con protocolo OTLP, por lo que no se necesita la dependencia upstream separada.

**Solución:** Cambiar el exporter por defecto de `otlp` a `cdi` en `src/main/resources/application.properties`:

```diff
- %prod.quarkus.otel.traces.exporter=${KEY49_OTEL_TRACES_EXPORTER:otlp}
+ %prod.quarkus.otel.traces.exporter=${KEY49_OTEL_TRACES_EXPORTER:cdi}
```

**Archivos modificados:**
- `src/main/resources/application.properties`

---

### 2. Docker Compose no leía `.env.prod`

**Síntoma:** Warnings múltiples al ejecutar `docker compose`:
```
The "KEY49_DB_NAME" variable is not set. Defaulting to a blank string.
The "KEY49_REDIS_PASSWORD" variable is not set. Defaulting to a blank string.
...
```

**Causa:** Docker Compose lee por defecto el archivo `.env` en el directorio del proyecto, pero el archivo de variables se llamaba `.env.prod`. Las variables de entorno `${VAR}` en `docker-compose.prod.yml` no se resolvían porque el archivo no era encontrado. Esto causó que servicios como Redis se iniciaran con contraseñas vacías y fallaran.

**Solución:** Crear un enlace simbólico:

```bash
cd /opt/key49
ln -sf .env.prod .env
```

**Nota para el script `setup-vps.sh`:** Debería crear este symlink automáticamente después de generar `.env.prod`.

---

### 3. RabbitMQ 3.13 rechaza variables de entorno deprecadas

**Error:**
```
error: RABBITMQ_VM_MEMORY_HIGH_WATERMARK is set but deprecated
error: deprecated environment variables detected
Please use a configuration file instead
```

El contenedor entraba en loop de reinicio porque RabbitMQ 3.13 trata las variables deprecadas como error fatal.

**Causa:** `docker-compose.prod.yml` definía:
```yaml
environment:
  RABBITMQ_VM_MEMORY_HIGH_WATERMARK: 0.6
  RABBITMQ_DISK_FREE_LIMIT: 1GB
```

**Solución:** Eliminar las variables deprecadas del compose. RabbitMQ 3.13 usa valores por defecto razonables (high watermark 0.4, disk free limit 50MB).

```diff
- environment:
-     RABBITMQ_DEFAULT_USER: ${KEY49_RABBITMQ_USER}
-     RABBITMQ_DEFAULT_PASS: ${KEY49_RABBITMQ_PASSWORD}
-     RABBITMQ_DEFAULT_VHOST: /
-     # Configuración de memoria
-     RABBITMQ_VM_MEMORY_HIGH_WATERMARK: 0.6
-     RABBITMQ_DISK_FREE_LIMIT: 1GB
+ environment:
+     RABBITMQ_DEFAULT_USER: ${KEY49_RABBITMQ_USER}
+     RABBITMQ_DEFAULT_PASS: ${KEY49_RABBITMQ_PASSWORD}
+     RABBITMQ_DEFAULT_VHOST: /
```

**Archivos modificados:**
- `docker-compose.prod.yml`

---

### 4. Validación de SMTP host vacío

**Error:**
```
Configuration validation failed:
java.util.NoSuchElementException: SRCFG00040: The config property
quarkus.mailer.host is defined as the empty String ("") which the
following Converter considered to be null
```

**Causa:** `.env.prod` definía `KEY49_SMTP_HOST=` (cadena vacía), lo cual es distinto de no definir la variable. Quarkus interpreta la cadena vacía como un valor inválido en vez de usar el default `localhost`. Además, el perfil `prod` requiere `STARTTLS=REQUIRED`.

**Solución:** En `.env.prod`:
```diff
- KEY49_SMTP_HOST=
- KEY49_SMTP_PORT=587
+ KEY49_SMTP_HOST=localhost
+ KEY49_SMTP_PORT=1025
+ KEY49_EMAIL_ENABLED=false
+ KEY49_SMTP_START_TLS=DISABLED
```

En `docker-compose.prod.yml`, service `key49`:
```diff
  env_file:
    - .env.prod
  environment:
+   - QUARKUS_MAILER_MOCK=true
    - KEY49_STORAGE_ENDPOINT=http://minio:9000
```

**Archivos modificados:**
- `.env.prod`
- `docker-compose.prod.yml`

---

### 5. PgBouncer MD5 vs PostgreSQL SCRAM-SHA-256

**Error en PgBouncer:**
```
ERROR S-0x...: key49/key49@172.28.0.4:5432 cannot do SCRAM authentication: wrong password type
WARNING C-0x...: pooler error: server login failed: wrong password type
```

**Causa:** PostgreSQL 16 usa `scram-sha-256` como método de autenticación por defecto (tanto en `pg_hba.conf` como en el hash de contraseñas). PgBouncer se configuraba con `auth_type = md5` y el `userlist.txt` usaba hash MD5. Además, la línea `host all all all scram-sha-256` en `pg_hba.conf` rechazaba cualquier intento de autenticación MD5 desde la red.

**Solución en tres partes:**

1. **Cambiar el hash de la contraseña en PostgreSQL a MD5:**
   ```sql
   SET password_encryption = 'md5';
   ALTER USER key49 WITH PASSWORD '<password>';
   ```

2. **Cambiar `pg_hba.conf` para aceptar MD5 en conexiones de red:**
   ```bash
   sed -i 's/host all all all scram-sha-256/host all all all md5/' \
       /var/lib/postgresql/data/pg_hba.conf
   ```

3. **Generar `userlist.txt` de PgBouncer con hash MD5 correcto:**
   ```bash
   PG_MD5=$(echo -n "${DB_PASS}key49" | md5sum | cut -d' ' -f1)
   echo "\"key49\" \"md5${PG_MD5}\"" > docker/pgbouncer/userlist.prod.txt
   ```

4. **Actualizar `init-prod.sh` para futuros despliegues:**
   ```sql
   SET password_encryption = 'md5';
   ALTER ROLE "$POSTGRES_USER" WITH PASSWORD '${POSTGRES_PASSWORD}';
   \! sed -i 's/scram-sha-256/md5/g' /var/lib/postgresql/data/pg_hba.conf
   ```

**Archivos modificados:**
- `docker/postgres/init-prod.sh` (script de inicialización)
- `docker/pgbouncer/userlist.prod.txt` (regenerado)

---

### 6. Bucket MinIO no creado

**Síntoma:** Health check mostraba:
```json
{"name":"MinIO bucket","status":"DOWN","data":{"bucket":"key49-documents"}}
```

**Causa:** El servicio `minio-init` (que crea el bucket al iniciar) no se ejecutó durante el despliegue manual porque `setup-vps.sh` falló en el paso 6 antes de ejecutarlo.

**Solución:** Ejecutar `minio-init` manualmente:
```bash
docker compose -f docker-compose.prod.yml up -d minio-init
```

El bucket se creó correctamente y Key49 pasó a estado **healthy**.

---

### 7. Traefik v3.3 incompatible con Docker 29.x

**Error en Traefik:**
```
ERR Failed to retrieve information of the docker client and server host
error="Error response from daemon: client version 1.24 is too old.
Minimum supported API version is 1.40, please upgrade your client
to a newer version"
```

**Causa:** Traefik v3.3 incluye un cliente Docker con API versión 1.24. Docker Engine 29.5.3 requiere API versión 1.40 o superior. Traefik no podía comunicarse con el socket de Docker para descubrir contenedores.

**Solución:** Actualizar la imagen de Traefik a la última versión estable:

```diff
- image: traefik:v3.3
+ image: traefik:latest
```

La versión instalada es **Traefik v3.7.4**, que incluye un cliente Docker compatible con API 1.40+.

**Archivos modificados:**
- `docker-compose.prod.yml`

---

### 8. Email ACME no expandido en configuración de Traefik

**Error:**
```
ERR Unable to obtain ACME certificate for domains
error="cannot get ACME client acme: error: 400 :: urn:ietf:params:acme:error:invalidContact
:: unable to parse email address"
```

**Causa:** El archivo `docker/traefik/traefik.yml` usaba `${KEY49_ACME_EMAIL}` para el email de Let's Encrypt, esperando que Traefik expandiera la variable de entorno. Sin embargo, la expansión de variables de entorno en archivos de configuración estática de Traefik solo funciona en ciertas versiones o con sintaxis específica. La variable no se expandió y Let's Encrypt recibió el literal `${KEY49_ACME_EMAIL}` como email.

**Solución:** Hardcodear el email directamente en el archivo de configuración:

```diff
- email: "${KEY49_ACME_EMAIL}"
+ email: "patriciovalarezo@gmail.com"
```

**Archivos modificados:**
- `docker/traefik/traefik.yml`

---

### 9. Raíz del dominio (`/`) devolvía 404 en vez de redirigir al portal

**Error en el navegador:**
```json
{"error":{"code":"NOT_FOUND","message":"Resource not found"}}
```

**Causa:** Al acceder a `https://key49.apx5.com/` (la raíz del dominio), Quarkus devolvía un 404 JSON porque:

1. **No existía ningún endpoint mapeado a `/`.** No había un `@Path("/")` ni un `index.html` estático. La raíz simplemente no estaba contemplada.

2. **El `ApiKeyAuthFilter` bloqueaba la raíz.** El filtro de autenticación por API key tiene una lista blanca de rutas públicas (`isPublicPath`). La raíz `/` no estaba en esa lista, por lo que el filtro exigía un header `Authorization: Bearer ...` incluso para la raíz, retornando 401. Aunque se agregara un recurso, el filtro lo interceptaba antes.

**Solución en dos partes:**

1. **Crear `RootRedirectResource.java`** — un nuevo endpoint JAX-RS que responde a `GET /` con un redirect HTTP 303 a `/portal/login`:

```java
@Path("/")
public class RootRedirectResource {
    @GET
    public Response redirectToPortal() {
        return Response.seeOther(URI.create("/portal/login")).build();
    }
}
```

2. **Agregar la raíz a `isPublicPath` en `ApiKeyAuthFilter.java`** para que el filtro no intercepte la petición:

```diff
  private boolean isPublicPath(String path) {
      return path.startsWith("/q/")
              || path.startsWith("/portal")
              || path.startsWith("/v1/admin/")
              || path.equals("/openapi")
              || path.equals("/swagger-ui")
+             || path.equals("/") || path.isEmpty();
  }
```

**Resultado:**
- `https://key49.apx5.com/` → **HTTP 303** redirect a `/portal/login`
- `https://key49.apx5.com/portal/login` → **HTTP 200** (página de login HTML)

**Advertencia durante la solución:** La imagen Docker usaba cache de capas (`COPY src/ src/` cacheado), por lo que el nuevo archivo `RootRedirectResource.java` no se incluía en la compilación aunque el `docker build` reportara éxito. Fue necesario usar `--no-cache` para forzar la inclusión del nuevo código fuente.

**Archivos creados/modificados:**
- `src/main/java/auracore/key49/api/RootRedirectResource.java` **(nuevo)** — endpoint de redirect raíz
- `src/main/java/auracore/key49/api/filter/ApiKeyAuthFilter.java` — `isPublicPath()` incluye `/`

---

## Estado final del despliegue

| Servicio | Contenedor | Estado |
|----------|-----------|--------|
| Traefik (SSL) | `key49-traefik` | ✅ Healthy |
| Key49 (Quarkus) | `key49-app` | ✅ Healthy |
| PostgreSQL 16 | `key49-postgres` | ✅ Healthy |
| PgBouncer | `key49-pgbouncer` | ✅ Healthy |
| Redis 7 | `key49-redis` | ✅ Healthy |
| RabbitMQ 3.13 | `key49-rabbitmq` | ✅ Healthy |
| MinIO | `key49-minio` | ✅ Healthy |

### URLs activas

| Recurso | URL |
|----------|-----|
| Health Check | `https://key49.apx5.com/q/health` |
| Swagger UI | `https://key49.apx5.com/q/swagger-ui` |
| API REST | `https://key49.apx5.com/v1/invoices` |
| Raíz → Portal | `https://key49.apx5.com/` → redirect a `/portal/login` |
| Portal Login | `https://key49.apx5.com/portal/login` |

### Secretos

Los secretos se encuentran en `/root/key49-secrets.txt` (permisos `600`, solo root).

### Archivos modificados durante el despliegue

| Archivo | Cambio |
|---------|--------|
| `src/main/resources/application.properties` | Exporter OTLP → CDI |
| `docker-compose.prod.yml` | Traefik v3.3→latest, eliminar vars RabbitMQ deprecadas, añadir QUARKUS_MAILER_MOCK |
| `.env.prod` | SMTP config, email deshabilitado para despliegue inicial |
| `docker/traefik/traefik.yml` | Email ACME hardcodeado |
| `docker/postgres/init-prod.sh` | Forzar MD5 + modificar pg_hba.conf |
| `docker/pgbouncer/userlist.prod.txt` | Regenerado con hash MD5 correcto |
| `.env` | Nuevo symlink → `.env.prod` |
| `pom.xml` | (cambios revertidos — no se modificó finalmente) |
| `src/main/java/.../RootRedirectResource.java` | **Nuevo** — redirect `GET /` → `/portal/login` |
| `src/main/java/.../ApiKeyAuthFilter.java` | Agregar `/` a `isPublicPath()` |

---

## Recomendaciones para futuros despliegues

1. **Automatizar el symlink `.env` → `.env.prod`** en `setup-vps.sh` paso 5.
2. **Actualizar `docker-compose.prod.yml`** para que PgBouncer use `auth_type=scram-sha-256` y generar userlist con SCRAM en vez de MD5 **(recomendado)**. Esto elimina la necesidad de degradar PostgreSQL a MD5.
3. **Configurar un SMTP real** para producción (Mailgun, SendGrid, etc.) y remover `QUARKUS_MAILER_MOCK=true`.
4. **Migrar `traefik.yml`** a usar `{{ env "KEY49_ACME_EMAIL" }}` (sintaxis Go templates de Traefik v3) en vez de hardcodear.
5. **Fijar versión de Traefik** a `traefik:v3.7` en vez de `latest` para evitar sorpresas en futuros despliegues.
