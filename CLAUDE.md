# AGENTS.md

This file defines how AI agents should work in this repository.

## Project Type

AList-TvBox is a Spring Boot 3 (Java 21) backend + Vue 3 admin UI system that:
- Aggregates cloud storage (AList ecosystem)
- Provides TVBox VOD APIs
- Supports Telegram media search
- Handles live streaming aggregation
- Provides plugin + filter system
- Runs embedded AList process

Base package: `cn.har01d.alist_tvbox`

---

## Critical Rules

### 1. Code Safety
- Never commit secrets (cookies, tokens, DB files, credentials)
- Never modify `main` directly
- Always use feature branches or git worktrees

### 2. Architecture Discipline
- Controller = thin layer only
- Business logic MUST go into Service layer
- Storage providers MUST follow Strategy pattern (`storage/`)
- DTOs MUST be top-level classes in `dto/`

### 3. Frontend Rules
- Vue 3 + TypeScript only
- SFC components required
- Models in `web-ui/src/model/`
- PascalCase filenames

---

## Build Rules

- Backend: `mvn clean package`
- Test: `mvn test`
- Run: `mvn spring-boot:run`
- Frontend dev: `cd web-ui && npm run dev`
- Frontend build: `npm run build`

---

## Git Workflow (MANDATORY)

1. Create worktree per task
2. Create feature branch inside worktree
3. Never touch main branch directly
4. Merge via PR
5. After merge:
   - delete worktree
   - delete branch
   - verify `git worktree list`

---

## Code Conventions

### Java
- 4-space indentation
- Lombok preferred (`@Data`, `@Slf4j`)
- camelCase methods, PascalCase classes

### Modules
- `web/` → controllers
- `service/` → business logic
- `storage/` → adapters
- `live/` → streaming
- `auth/` → security

---

## System Boundaries

DO NOT:
- put business logic in controllers
- bypass service layer
- mix storage implementations
- hardcode runtime configuration

DO:
- follow existing patterns
- reuse services
- keep API stable