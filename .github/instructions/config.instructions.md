---
applyTo: ["**/pom.xml", "**/application.properties", "**/application.yaml"]
---
# Configuración — Key49

## Maven
- Multi-módulo con parent POM
- NO agregar Lombok
- Preferir extensiones Quarkus sobre librerías genéricas
- Fijar versiones de dependencias (dependency pinning)

## Módulos
```
key49-api, key49-core, key49-xml, key49-signer, key49-sri,
key49-queue, key49-ride, key49-notify, key49-storage, key49-admin
```

## Properties clave
- `KEY49_TIMEZONE=America/Guayaquil`
- `KEY49_SRI_ENVIRONMENT=test` (test | production)
- `KEY49_OUTBOX_POLL_INTERVAL=500ms`
- `KEY49_OUTBOX_BATCH_SIZE=50`
- Secretos en variables de entorno, NUNCA hardcoded

## Profiles
- dev, test, prod
- DevServices activos solo en dev/test
