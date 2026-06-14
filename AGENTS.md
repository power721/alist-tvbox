# Repository Guidelines

## Project Structure & Module Organization

This is a Spring Boot 3 / Java 21 service with a Vue 3 management UI. Backend code lives in `src/main/java/cn/har01d/alist_tvbox/`: `web/` controllers, `service/` logic, `entity/` JPA models and repositories, `storage/` provider adapters, `dto/` objects, and `config/` wiring. Backend tests mirror that layout under `src/test/java/`.

Runtime resources are in `src/main/resources/`, including `application*.yaml`, SQL migrations under `db/migration/`, and native-image metadata. The frontend lives in `web-ui/`; Docker files are in `docker/`; scripts are in `scripts/`.

## Build, Test, and Development Commands

- `mvn clean package`: builds the backend JAR in `target/`.
- `mvn test`: runs Java unit and Spring tests.
- `mvn spring-boot:run`: starts the backend locally, normally on port `4567`.
- `mvn exec:java -Dexec.mainClass=cn.har01d.alist_tvbox.Main`: regenerates `src/main/resources/META-INF/native-image/reflect-config.json`.
- `cd web-ui && npm run dev`: starts Vite.
- `cd web-ui && npm run build`: type-checks and builds UI.

## Coding Style & Naming Conventions

Use standard Java conventions: 4-space indentation, PascalCase classes, camelCase methods and fields, and packages under `cn.har01d.alist_tvbox`. Keep controllers thin and rules in services. Storage providers should follow the existing `storage/` adapter pattern.

DTO classes returned by external APIs must be top-level classes in `cn.har01d.alist_tvbox.dto`. After adding any new `dto`, `entity`, or `model` class, run `cn.har01d.alist_tvbox.Main` to update native reflection config.

For the UI, use Vue single-file components with PascalCase filenames such as `DriverAccountView.vue`. TypeScript models live under `web-ui/src/model/`. Run `npm run lint` in `web-ui/`; it uses ESLint and Prettier.

## Testing Guidelines

Java tests use Spring Boot Test/JUnit conventions and should be named `*Test.java`, for example `service/TgProviderClientTest.java`. Prefer focused service tests and controller tests for API behavior. Frontend tests are `.test.mjs` files colocated with views, components, or utilities. Run `mvn test` for backend changes and `npm run build` plus relevant frontend tests or linting for UI changes.

## Commit & Pull Request Guidelines

Recent history mixes concise Chinese subject lines, such as `电报频道搜索`, with Conventional Commit-style messages such as `fix: handle empty tg provider channel lists`. Keep subjects short and specific; use `fix:` or `docs:` when it clarifies intent.

Pull requests should describe the user-visible change, list verification commands, and link related issues or plans. Include screenshots for UI changes and note configuration, migration, or Docker impacts.

## Security & Configuration Tips

Do not commit real cookies, tokens, database files, or local service credentials. Treat files such as `cookie.txt`, `config/data/`, and local YAML overrides as private runtime artifacts.

## Git Worktree Policy
1. Create a new Git worktree for every task.
2. Create and use a dedicated feature branch inside that worktree.
3. Do not make changes directly on the `main` branch.
4. Commit all changes to the task branch.
5. Merge the task branch into `main` through the normal review process.
6. After the merge is complete:
    * Remove the worktree (`git worktree remove <path>`).
    * Delete the merged branch (`git branch -d <branch>`).
    * Verify that no unused worktrees remain (`git worktree list`).
7. Keep only active worktrees in the repository.
