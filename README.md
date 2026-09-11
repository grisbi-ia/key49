# Key49

Plataforma SaaS multi-tenant de facturación electrónica para Ecuador (SRI).

## Descripción

Key49 expone APIs REST para que desarrolladores integren la emisión, firma (XAdES-BES), envío al SRI y entrega de comprobantes electrónicos desde cualquier sistema (ERP, POS, e-commerce, apps móviles).

## Stack Tecnológico

- **Java 25 LTS** + Quarkus 3.34
- **PostgreSQL 16** — multi-tenant (schema-per-tenant)
- **RabbitMQ** — procesamiento asíncrono de comprobantes
- **MinIO** — almacenamiento S3-compatible (XML, RIDE)
- **Redis** — cache, rate limiting, sesiones

## Estructura del Proyecto

**Módulo único Maven** (packaging `jar`). La separación lógica se logra por paquetes Java:

```
auracore.key49
├── api        → REST endpoints + portal web (Qute + HTMX + Pico CSS)
├── core       → Entidades, servicios, repositorios, enums SRI
├── xml        → Generación XML, validación XSD, clave de acceso
├── signer     → Firma XAdES-BES, gestión de certificados .p12
├── sri        → Cliente SOAP (Recepción + Autorización)
├── queue      → Consumers/Producers RabbitMQ, reintentos
├── ride       → Generación RIDE (PDF)
├── notify     → Email, webhooks
├── storage    → MinIO/S3
└── admin      → Métricas, health checks
```

## Requisitos

- Java 25+
- Maven 3.9+
- Docker (DevServices levanta PostgreSQL y RabbitMQ; **Redis debe correr en `localhost:6379`**: `docker compose up -d redis`)

## Desarrollo

```bash
# Compilar
mvn clean compile

# Ejecutar tests (requiere Redis en localhost:6379: docker compose up -d redis)
mvn verify

# Modo desarrollo (hot reload)
mvn quarkus:dev
```

## Scripts

Los ejecutables operativos están en [`scripts/`](scripts/):

| Script | Propósito | Uso |
| ------ | --------- | --- |
| [`scripts/setup-vps.sh`](scripts/setup-vps.sh) | Bootstrap completo de un VPS Ubuntu nuevo (Docker, firewall, secretos, despliegue) | `sudo bash scripts/setup-vps.sh` (desde `/opt/key49`) |
| [`scripts/package-for-vps.sh`](scripts/package-for-vps.sh) | Compila y empaqueta el artefacto para desplegar en el VPS | `./scripts/package-for-vps.sh` |
| [`scripts/generate-secrets.sh`](scripts/generate-secrets.sh) | Genera contraseñas seguras para `.env.prod` | `./scripts/generate-secrets.sh` |
| [`scripts/test-curls.sh`](scripts/test-curls.sh) | Pruebas manuales de emisión en desarrollo (`localhost:8080`) | `KEY49_API_KEY=... ./scripts/test-curls.sh factura` |
| [`scripts/test-curls-prod.sh`](scripts/test-curls-prod.sh) | Pruebas manuales de emisión contra producción | `KEY49_API_KEY=... ./scripts/test-curls-prod.sh factura` |

> El procedimiento completo de despliegue (manual y asistido por agente) y el
> acceso a la base de datos por SSH están en [`docs/DEPLOY-VPS.md`](docs/DEPLOY-VPS.md).
> Los scripts de mantenimiento de base de datos viven en [`db/maintenance/`](db/maintenance/)
> y se documentan en [`docs/DB-ADMIN.md`](docs/DB-ADMIN.md).

## Documentación

La documentación técnica completa se encuentra en [`docs/`](docs/):

| Documento                               | Descripción                                |
| --------------------------------------- | ------------------------------------------ |
| [SPEC.md](docs/SPEC.md)                 | Especificación del producto, normativa SRI |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Decisiones técnicas, paquetes, patrones    |
| [DATABASE.md](docs/DATABASE.md)         | Schema PostgreSQL completo                 |
| [API.md](docs/API.md)                   | Contrato REST API, errores, webhooks       |
| [CONVENTIONS.md](docs/CONVENTIONS.md)   | Convenciones de código y testing           |
| [TASKS.md](docs/TASKS.md)               | Roadmap de desarrollo por fases            |
| [DEPLOY-VPS.md](docs/DEPLOY-VPS.md)     | **Despliegue en producción (VPS)**: manual o asistido por agente, rollback y acceso a BD por SSH |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md)     | Despliegue de infraestructura y ambiente de pruebas |
| [OPERATIONS.md](docs/OPERATIONS.md)     | Operación, colas, estados y resiliencia    |
| [DB-ADMIN.md](docs/DB-ADMIN.md)         | Administración de PostgreSQL y tenants     |

## Autor

**Patricio Valarezo** — patriciovalarezo@gmail.com

## Licencia

© 2026 **AURACORE SAS**. Todos los derechos reservados.
