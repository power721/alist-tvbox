# Spring Boot 4.0 升级风险评估

- **日期**:2026-06-21
- **现状**:Spring Boot 3.5.15 + Java 21
- **目标**:Spring Boot 4.0(2025-11-30 GA,基于 Spring Framework 7.0 / Jakarta EE 11)
- **关键决策**:**jar 构建优先,保留 Native Image(static + musl)为可选构建路径**
- **总体风险(JVM jar)**:**低-中**,核心工作量 1-2 人天(Jackson 3 迁移除外)
- **总体风险(Native Image)**:**高**,需单独验证 GraalVM / Hibernate 7 / Flyway / sqlite-jdbc 组合

---

## 1. 背景与基线

Spring Boot 4.0 已于 2025-11-30 正式 GA,核心变化:

- 底层升级到 **Spring Framework 7.0**、**Jakarta EE 11**(Servlet 6.1、JPA 3.2、Bean Validation 3.1)
- **模块化**:单体 `spring-boot-autoconfigure` 拆成几十个细粒度模块,starter 体系重构(最大破坏点)
- **Jackson 2 → Jackson 3**(`com.fasterxml.jackson` → `tools.jackson`)
- **Spring Security 7.0**、**Spring Data 2025.1**、Hibernate 7、Flyway 11+
- Java 17 baseline(本项目 Java 21 ✓)

### 关键决策:保留 Native Image 配置

本项目原以 GraalVM native image(static + musl)为核心交付物之一。升级时采用 **jar 构建优先**,但保留 native-image profile、反射/资源配置和生成工具。Native Image 不作为 Spring Boot 4 迁移的基础验收门槛,需要在 JVM jar 跑通后用 `mvn clean package -Pnative` 单独验证。

---

## 2. 已天然规避的风险(基线满足)

本项目代码已规避了 Spring Boot 4.0 的多个常见雷区:

| 官方破坏项 | 本项目命中 | 影响 |
|---|---|---|
| Java 17 baseline | Java 21 | ✅ 无 |
| Undertow 移除 | 用默认 Tomcat | ✅ 无 |
| `@MockBean`/`@SpyBean` 移除 | 已用 `@MockitoBean`(0 处旧用法) | ✅ 无 |
| `WebSecurityConfigurerAdapter` 移除 | 0 处 | ✅ 无 |
| `spring-boot-starter-aop` → aspectj 重命名 | 0 处 `@Aspect` | ✅ 无 |
| `EnvironmentPostProcessor`/`BootstrapRegistry`/`spring.factories` 移包 | 0 处 | ✅ 无 |
| `@EntityScan` 包路径变更 | 0 处 | ✅ 无 |
| `@JsonComponent`/`@JsonMixin` 重命名 | 0 处 | ✅ 无 |
| Jackson 属性重命名(`spring.jackson.read/write.*`) | 配置中无此类属性 | ✅ 无 |
| MongoDB / Session / DevTools 属性重命名 | 配置中无 | ✅ 无 |

> 代码扫描数据(2026-06-21):测试文件 54 个;`@MockBean`/`@SpyBean` 0 处、`@MockitoBean` 已在用;Jackson import 106 文件、ObjectMapper 直接用 46 文件;RestTemplate 36 文件、RestTemplateBuilder 35 处;SecurityFilterChain 1 个。

---

## 3. Native Image 保留后的额外风险

Native Image 是升级中最大且最不可控的风险。保留配置后,以下项仍需单独验证:

| 原 native 相关风险 | 状态 |
|---|---|
| GraalVM native-image v25+ 构建环境升级 | ⚠️ 需验证 |
| 76KB 手写 `reflect-config.json` 全量重生成/验证 | ⚠️ 保留并需维护 |
| `proxy-config.json` / `resource-config.json` / `native-image.properties` | ⚠️ 保留并需维护 |
| Hibernate 7 BytecodeProvider / enhance 插件的 native 适配 | ⚠️ 需验证 |
| Flyway `--initialize-at-build-time=org.flywaydb` hack 重测 | ⚠️ 需验证 |
| sqlite-jdbc static musl 构建 | ⚠️ 需验证 |
| PostgreSQL native-image 验证(已知 outstanding task) | ⚠️ 仍需验证 |

---

## 4. 剩余风险分级(JVM jar 视角)

### 🔴 高:Jackson(量大,但可绕)

- **106 个文件** `import com.fasterxml.jackson.*`,**46 个文件**直接使用 `ObjectMapper`。
- 包名 `com.fasterxml.jackson` → `tools.jackson` 是硬破坏(编译不过),纯体力活但量大。
- **缓解路径**:Spring Boot 4.0 提供 `spring-boot-jackson2` stop-gap 模块 + `spring.http.converters.preferred-json-mapper=jackson2` / `spring.jackson2.*`,可**整体保留 Jackson 2**,先把框架升上去,Jackson 3 迁移作为独立后续任务。
- ⚠️ 注意:Lombok ↔ Jackson 3 社区报告有兼容问题,本项目重度依赖 Lombok。

### 🟡 中:常规适配(确定性强,工作量小)

#### 4.1 Flyway 依赖坐标

- 官方明确:使用 Flyway 现在必须改为 **`spring-boot-starter-flyway`**(或模块 `spring-boot-flyway`),否则 autoconfiguration 不再生效。
- 现状:直接依赖 `flyway-core` / `flyway-mysql` / `flyway-database-postgresql`(第三方坐标)。**需补 `spring-boot-starter-flyway`**,原有第三方坐标保留。
- Flyway **Java migration**(V2/V3,继承 `BaseJavaMigration`)走 SPI,不受坐标变更影响。
- 用了 1 处 `FlywayConfigurationCustomizer`,模块化后需确认包路径。

#### 4.2 测试基础设施

- `@SpringBootTest` **不再自动提供** MockMvc / TestRestTemplate / WebClient。
  - 8 个测试用到 MockMvc/TestRestTemplate → 需逐一加 `@AutoConfigureMockMvc` / `@AutoConfigureTestRestTemplate`,并加 `spring-boot-resttestclient` 依赖。
- `MockitoTestExecutionListener` 已移除 → 若有 `@Mock`/`@Captor` 字段需改用 `MockitoExtension`。
- `@WithMockUser` 需要 `spring-boot-starter-security-test`。

#### 4.3 Spring Security 7.0

- 1 个 `SecurityFilterChain` + 1 处 `requestMatchers`,面不大。
- **主要隐患是 Security 7 的 CSRF 默认行为变化**,可能**静默破坏 stateless 的 CLIENT/API**(本项目核心是 TVBox VOD API + X-API-KEY 模式)。需重点测试 API 鉴权链路。

#### 4.4 `RestTemplateBuilder`(35 处)

- 重度使用 `org.springframework.boot.web.client.RestTemplateBuilder`。RestTemplate 未被移除,但其 Builder 在模块化后迁入 `spring-boot-restclient` 模块。35 处 import 的包路径**可能需调整**,需验证。

#### 4.5 starter 重命名

- `spring-boot-starter-web` → `spring-boot-starter-webmvc`(deprecated bridge 在 4.0 仍可用)。
- **风险被官方缓解**:可先保留旧名,后续清理。`spring-boot-starter-security`、`spring-boot-starter-data-jpa` 名称未变。

### 🟢 低:仅需确认

- **Lombok 1.18.38** 对 Spring 7 注解处理——编译时验证即可。
- **固定版本的第三方依赖**(不受 Boot BOM 管理):`sqlite-jdbc 3.50.2.0`、`jsoup 1.15.4`(偏旧)、`okhttp 4.12.0`、`commons-text 1.10.0`、`commons-compress 1.27.1`、`zxing 3.5.3`、`tars-core 1.7.4`。纯解析/工具库基本不受 Spring 影响。
- **JSpecify nullability**:4.0 全面加注解,可能产生编译警告(非破坏)。
- **properties migrator**:建议升级时加 `spring-boot-properties-migrator` 运行一次。

---

## 5. Native Image 保留清单

升级分支保留以下 native 相关配置,但不把 native build 作为 JVM jar 迁移的基础验收门槛:

- `pom.xml` 的 `<profile id="native">`(`hibernate-enhance-maven-plugin` + `native-maven-plugin` + buildArgs)
- `src/main/resources/META-INF/native-image/`
  - `reflect-config.json`(76KB)
  - `resource-config.json` / `proxy-config.json` / `native-image.properties`
- `Main.java` 反射配置生成工具
- `NativeFlywayMigrationConfig` native-only Java migration 注册

> 注:`hibernate-enhance-maven-plugin` 仍只放在 native profile 内,JVM jar 不依赖它。

---

## 6. 推荐迁移路径

保留 native 后,迁移路径分两段:

1. **升 Boot 4 + `spring-boot-starter-classic` + `spring-boot-jackson2`**(保留 Jackson 2)→ jar 先跑通
2. 修 Flyway starter / 测试注解 / Security
3. 跑全量测试 + 手测鉴权链路(X-API-KEY、Basic Auth、session token)
4. 保留 native profile 和配置,确认普通 JVM package 不受影响
5. 后续单独执行 `mvn clean package -Pnative`,修正 GraalVM/Hibernate/Flyway/sqlite native 问题
6. (可选,后续)清理 deprecated starter、迁移 Jackson 3

**预计 JVM jar 核心工作量:1-2 人天**(Jackson 3 迁移除外)。Native Image 验证按独立任务估算。

---

## 7. 未决问题

1. **Jackson 3 迁移做不做?** 不做 → 长期依赖 deprecated 的 `spring-boot-jackson2`(未来某版移除);做 → 独立的一坨包名重命名工作(106 文件)。
2. **Native Image 是否仍作为发布阻断项?** 当前建议不阻断 JVM jar 迁移,后续单独验。
3. **tars-core 1.7.4**:腾讯 TARS,非 Spring 生态,需单独确认与 Spring 7 / Jakarta EE 11 共存。

---

## 8. 参考来源

- [Spring Boot 4.0 Migration Guide(官方,权威)](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot 4.0.0 available now(spring.io)](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now)
- [Spring Framework 7.0 GA(spring.io)](https://spring.io/blog/2025/11/13/spring-framework-7-0-general-availability)
- [Introducing Jackson 3 support in Spring(spring.io)](https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring)
- [Modularizing Spring Boot(spring.io)](https://spring.io/blog/2025/10/28/modularizing-spring-boot)
- [Migrating to Spring Security 7.0(官方)](https://docs.spring.io/spring-security/reference/migration/index.html)
