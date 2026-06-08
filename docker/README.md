# ╔══════════════════════════════════════════════════════════════╗
# ║  Key49 — Docker & Despliegue                                ║
# ╚══════════════════════════════════════════════════════════════╝

## 🖥️ Desarrollo Local

### Requisitos
- Docker 24+
- Docker Compose v2
- Java 25 + Maven 3.9+ (para compilar la app)

### Levantar infraestructura

```bash
# Iniciar todos los servicios (PostgreSQL, Redis, RabbitMQ, MinIO)
docker compose up -d

# Verificar estado
docker compose ps

# Ver logs
docker compose logs -f postgres
```

La primera vez que se ejecuta, PostgreSQL se inicializa automáticamente con:
- Tablas del esquema `public` (tenants, api_keys, etc.)
- Esquema `tenant_template` con todas las tablas
- Tenant de demo: RUC `1790016919001`
- API Key de demo: `k49_DemoKey49DevLocalTest0000`

### URLs de desarrollo

| Servicio  | URL                        | Usuario / Password |
|-----------|----------------------------|---------------------|
| API       | http://localhost:8080      | —                   |
| Portal    | http://localhost:8080/portal/login | —           |
| PostgreSQL| localhost:5433             | postgres / 1234abcd |
| Redis     | localhost:6379             | (sin password)      |
| RabbitMQ  | http://localhost:15672     | guest / guest       |
| MinIO     | http://localhost:9001      | minioadmin / minioadmin |
| Swagger   | http://localhost:8080/q/swagger-ui | —            |
| Health    | http://localhost:8080/q/health | —               |

### Compilar y ejecutar la aplicación

```bash
# Compilar
mvn clean compile

# Ejecutar en modo dev (hot reload)
mvn quarkus:dev

# Ejecutar tests
mvn verify
```

---

## 🚀 Producción (VPS Ubuntu Server)

### Requisitos del VPS

- Ubuntu Server 22.04 o 24.04
- Docker 24+ y Docker Compose v2
- Dominio apuntando al VPS: `key49.apx5.com` → IP del servidor
- Puertos 80 y 443 abiertos en el firewall

### Instalación rápida en VPS

```bash
# 1. Instalar Docker (si no está)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
# Cerrar sesión y volver a entrar

# 2. Clonar el repositorio
git clone <repo-url> /opt/key49
cd /opt/key49

# 3. Generar secretos y configurar .env.prod
./docker/generate-secrets.sh
# Copia los valores generados a .env.prod
nano .env.prod

# 4. Desplegar
./docker/deploy.sh
```

### Estructura de archivos para producción

```
key49/
├── docker-compose.prod.yml    # Orquestación de producción
├── Dockerfile                  # Build multi-stage de la app
├── .env.prod                   # Variables de entorno (¡con secretos reales!)
├── docker/
│   ├── deploy.sh               # Script de despliegue
│   ├── generate-secrets.sh     # Generador de contraseñas seguras
│   ├── traefik/
│   │   └── traefik.yml         # Reverse proxy + SSL (Let's Encrypt)
│   ├── pgbouncer/
│   │   ├── pgbouncer.prod.ini  # Connection pooler config
│   │   └── userlist.prod.txt   # Credenciales (generado por deploy.sh)
│   └── postgres/
│       └── init-prod.sh        # Inicialización de BD
└── db/migrations/              # Scripts SQL de migración
```

### Servicios en producción

| Servicio  | Puerto interno | Descripción                              |
|-----------|---------------|------------------------------------------|
| Traefik   | 80, 443       | Reverse proxy, SSL automático            |
| Key49     | 8080          | Aplicación Quarkus                       |
| PostgreSQL| 5432          | Base de datos                            |
| PgBouncer | 6432          | Connection pooler                        |
| Redis     | 6379          | Cache, rate limiting, sesiones           |
| RabbitMQ  | 5672, 15672   | Colas de mensajes                        |
| MinIO     | 9000, 9001    | Almacenamiento S3 (XML, RIDE)            |

Solo los puertos **80 y 443** están expuestos al exterior (vía Traefik). Todos los demás servicios se comunican por la red interna `key49-net`.

### Comandos útiles en producción

```bash
# Ver todos los servicios
docker compose -f docker-compose.prod.yml ps

# Ver logs de la aplicación
docker compose -f docker-compose.prod.yml logs -f key49

# Ver logs de un servicio específico
docker compose -f docker-compose.prod.yml logs -f postgres
docker compose -f docker-compose.prod.yml logs -f traefik

# Reiniciar la aplicación (tras actualizar código)
docker compose -f docker-compose.prod.yml up -d --build key49

# Reiniciar todo
docker compose -f docker-compose.prod.yml down
docker compose -f docker-compose.prod.yml up -d

# Ver uso de recursos
docker stats

# Backup de la base de datos
docker exec key49-postgres pg_dump -U key49 key49 > backup_$(date +%Y%m%d).sql

# Restaurar base de datos
docker exec -i key49-postgres psql -U key49 key49 < backup_20260101.sql

# Ver certificados SSL (Traefik)
docker exec key49-traefik ls -la /letsencrypt/
```

### Actualizar la aplicación

```bash
cd /opt/key49
git pull
docker compose -f docker-compose.prod.yml up -d --build key49
```

### Firewall (UFW) recomendado

```bash
sudo ufw allow 22/tcp    # SSH
sudo ufw allow 80/tcp    # HTTP (redirige a HTTPS)
sudo ufw allow 443/tcp   # HTTPS
sudo ufw enable
```

---

## 🔐 Seguridad

1. **NUNCA** subas `.env.prod` al repositorio (ya está en `.gitignore`)
2. Cambia **TODOS** los passwords antes de desplegar (usa `./docker/generate-secrets.sh`)
3. La clave maestra (`KEY49_MASTER_KEY`) cifra los certificados .p12 — si se pierde, **se pierden los certificados**
4. Guarda una copia segura de `.env.prod` fuera del servidor
5. Los certificados SSL se renuevan automáticamente (Let's Encrypt)
