---
applyTo: ["**/pom.xml", "**/application.properties", "**/application.yaml"]
---

# Configuración — Key49

## Maven

- Módulo único (packaging `jar`)
- NO agregar Lombok
- Preferir extensiones Quarkus sobre librerías genéricas
- Fijar versiones de dependencias (dependency pinning)

## Paquetes Java

```
auracore.key49.{api, core, xml, signer, sri, queue, ride, notify, storage, admin}
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
