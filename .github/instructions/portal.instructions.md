---
applyTo: ["**/templates/**/*.html", "**/portal/**"]
---
# Portal Web — Key49

## Tecnología
- Server-side rendering con Qute (Quarkus)
- Pico CSS (~10KB, estilos sin clases — solo HTML semántico)
- HTMX (~14KB, interactividad sin JavaScript manual)

## Rutas
- `/portal/login` — Login con API key
- `/portal/` — Dashboard: tabla de documentos + filtros + paginación
- `/portal/documents/{id}` — Detalle + timeline + descargas

## Reglas
- Solo lectura — no crea ni modifica documentos
- Autenticación por sesión/cookie (Redis-backed, TTL 30min)
- Templates en `src/main/resources/templates/portal/`
- Paquete Java: `auracore.key49.api.portal`
- HTMX usa GET para actualizaciones parciales (no CSRF necesario)
