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
