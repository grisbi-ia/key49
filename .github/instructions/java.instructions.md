---
applyTo: "**/*.java"
---

# Reglas Java — Key49

## General

- Java 25: usar records, sealed interfaces, pattern matching, text blocks
- NO Lombok — usar records para DTOs
- Paquete base: `auracore.key49.{module}.{layer}`
- Logs: `@Inject Logger log` o `Log.info()` (JBoss Logging)
- `var` para variables locales donde el tipo es obvio
- `Optional` para retornos, NUNCA para parámetros

## Reactive

- Servicios retornan `Uni<T>` (Mutiny)
- Operaciones SOAP usan `@Blocking`

## Entidades

- Tablas tenant NO tienen columna `tenant_id`
- Usar `@Version` para optimistic locking
- Campos `createdAt`, `updatedAt` obligatorios

## State Machine

- Nunca asignar `document.status = X` directamente
- Usar `document.transitionTo(target)` que llama `canTransitionTo()`

## Zona Horaria

- `LocalDate.now(Key49Constants.EC_ZONE)` — NUNCA `LocalDate.now()` sin zona
- `Key49Constants.EC_ZONE = ZoneId.of("America/Guayaquil")`

## Catálogos SRI

- Enums en key49-core: DocumentType, DocumentStatus, TaxType, VatRate, PaymentMethod, IdentificationType
- Cada enum tiene `sriCode()` y `fromSriCode(String)`

## XML para SRI (excepción de idioma)

- Los builders XML usan nombres en español según XSD: `infoTributaria`, `razonSocial`, `fechaEmision`
