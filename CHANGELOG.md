# Changelog

Todos los cambios notables de este proyecto se documentan en este archivo.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/es/1.1.0/),
y este proyecto adhiere a [Semantic Versioning](https://semver.org/lang/es/).

## [0.1.0] - 2026-04-05

### Agregado

- Inicialización del proyecto Quarkus multi-módulo con Maven (T-001)
- Estructura de 10 módulos: api, core, xml, signer, sri, queue, ride, notify, storage, admin
- Parent POM con Quarkus 3.34.2 BOM y dependency management
- Perfiles Maven: dev, test, prod
- Configuración base en `application.properties`
- Documentación técnica organizada en `docs/`
- README.md informativo del proyecto
- .gitignore configurado
