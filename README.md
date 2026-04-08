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
- Docker (para DevServices en desarrollo: PostgreSQL, RabbitMQ, Redis)

## Desarrollo

```bash
# Compilar
mvn clean compile

# Ejecutar tests
mvn verify

# Modo desarrollo (hot reload)
mvn quarkus:dev
```

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

## Autor

**Patricio Valarezo** — patriciovalarezo@gmail.com

## Licencia

© 2026 **AURACORE SAS**. Todos los derechos reservados.
