# backend (ccproxy) — conventions

Kotlin / Spring Boot 3.4 / Java 21 / Maven. Seeded from MicroTeams' backend with business logic
removed; the conventions below are inherited and still authoritative.

- **One contract, generated.** `../CCProxy-API.yml` is the source of truth. `./mvnw` regenerates
  `app.microteams.ccproxy.api.*Api` + `app.microteams.ccproxy.model.*DTO` (openapi-generator,
  copied into `src/` by the antrun `copy-generated-api` step — **do not edit generated files**). The
  generator names an `Api` after a path's first segment; each module's single controller implements
  exactly its own interface.
- **Packages.** CCProxy code is `app.microteams.ccproxy.*`. The borrowed authorization
  framework and its leaf kernel keep their original `org.rucca.cheese.{auth,common}` packages (so
  they can one day be extracted); the component scan covers both roots.
- **Authorization is all in one place.** `org.rucca.cheese.auth` (`@Guard(action, resourceType)` +
  `AuthorizationAspect` + custom-logic expressions) is the whole model. The concrete rules live in
  `app.microteams.ccproxy.authz.RolePermissionService` — one row per endpoint, no authorization
  in business code. Today it only grants the `/ping` smoke test; real rows (tenant/customer/account/
  machine/api-key) are added there as endpoints land.
- **Persistence.** Postgres, schema `ccproxy` (`hibernate.default_schema`). Entities extend
  `BaseEntity` (SEQUENCE id + created/updated/deletedAt), soft-delete via `@SQLRestriction`, enums as
  strings, and rely on the `all-open` compiler plugin (never make entities `data class`).
- **Errors.** Throw `BaseError` subclasses; the global handler maps them. Success responses are bare
  DTOs.
- **Tests are integration tests** against a real Postgres (`./scripts/dependency-start.sh`).
- Every Kotlin file starts with a `/* Description / Author(s) */` header; code + comments in English;
  spotless(ktfmt) enforced (`./mvnw spotless:apply`).
