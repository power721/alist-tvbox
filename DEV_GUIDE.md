# DEV_GUIDE.md

## Build System

Backend:
mvn clean package
mvn spring-boot:run
mvn test

Frontend:
cd web-ui
npm install
npm run dev
npm run build
npm run lint

---

## Docker

./build-docker.sh

docker run -d -p 4567:4567 -p 5344:80 -e ALIST_PORT=5344 -v /opt/alist-tvbox:/data --name xiaoya-tvbox haroldli/xiaoya-tvbox:latest

---

## Native Build

mvn exec:java -Dexec.mainClass=cn.har01d.alist_tvbox.Main
./build-native.sh

---

## Testing

Backend:
mvn test
mvn test -Dtest=TvBoxServiceTest

Frontend:
- colocated .test.mjs

---

## Project Structure

backend → src/main/java
frontend → web-ui/
config → application*.yaml
docker → docker/
scripts → scripts/

---

## Coding Standards

Java:
- Lombok required
- Service-first design
- 4-space indent

DTO:
- top-level only
- dto/ package required

Frontend:
- Vue 3 + TypeScript
- PascalCase components

---

## Configuration

Profiles:
dev / docker / mysql / host / production / xiaoya

Key configs:
- datasource
- app.sites
- app.tgSearch
- app.localProxyConfig

---

## Debug Notes

- AList runs as subprocess
- Telegram scraping depends on HTML structure
- Live streams are unstable
- Cache affects freshness

---

## Git Workflow

1. worktree per task
2. feature branch per task
3. no direct main commits
4. PR-based merge
5. cleanup after merge


---

## AI Coding Rules (Codex / Claude)

### General Principles
- Prefer **small, incremental changes**
- Never perform large-scale refactors unless explicitly requested
- Maintain backward compatibility at all times
- Keep changes **local to the feature/module**

---

### Codex Mode (Patch Executor)
- Work in **minimal diff patches**
- Do NOT rename files, classes, or public APIs
- Do NOT reformat code or style-only changes
- One logical change per commit
- Ensure code compiles after each step
- Avoid cross-module modifications unless required
- Always respect existing architecture boundaries

---

### Claude Mode (Architecture Reasoning)
- Focus on system design and impact analysis
- Propose changes before implementation
- Highlight risks and dependency impact
- Prefer extending existing services over creating new layers
- Avoid unnecessary abstraction

---

### Forbidden Actions (All AI)
- No mass refactoring without explicit request
- No moving packages or reorganizing structure
- No silent API changes
- No mixing controller/service/storage responsibilities
- No deleting unused code unless confirmed

---

### Safe Refactor Rules
- Keep controller → service → repository flow intact
- Prefer additive changes over modifications
- Reuse existing DTOs and services
- Introduce new modules only when necessary
