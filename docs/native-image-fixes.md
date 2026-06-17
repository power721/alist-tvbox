# Native Image Startup Fixes

## Issues Fixed

### 1. Hibernate ByteBuddy Error (FATAL)
**Error**: `java.util.ServiceConfigurationError: org.hibernate.bytecode.spi.BytecodeProvider: Provider org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl not found`

**Root Cause**: ByteBuddy bytecode enhancement doesn't work in GraalVM native-image. The JIT-based bytecode manipulation that Hibernate normally uses is incompatible with ahead-of-time compilation.

**Solution**: 
- Created `/src/main/resources/META-INF/native-image/native-image.properties` with:
  ```properties
  Args = -Dhibernate.bytecode.provider=none \
         -Dhibernate.enable_lazy_load_no_trans=false
  ```
- Added `org.hibernate.bytecode.internal.none.BytecodeProviderImpl` to `reflect-config.json`
- Build-time bytecode enhancement is already configured via `hibernate-enhance-maven-plugin` in the native profile

### 2. Flyway Scanner Warning (NON-FATAL)
**Warning**: `Unable to scan location: /db/migration/current (unsupported protocol: resource)`

**Root Cause**: Flyway's classpath scanner can't dynamically scan directories in native-image because the filesystem abstraction differs at runtime.

**Status**: Already properly configured
- `resource-config.json` includes pattern: `{"pattern": "db/migration/current/.*\\.sql"}`
- This registers all SQL files at build time
- The warning is cosmetic - Flyway will still find and execute migrations

## Files Modified

1. **NEW**: `src/main/resources/META-INF/native-image/native-image.properties`
   - Configures Hibernate to use 'none' bytecode provider
   - Disables lazy initialization that requires runtime bytecode manipulation

2. **MODIFIED**: `src/main/resources/META-INF/native-image/reflect-config.json`
   - Added reflection configuration for `org.hibernate.bytecode.internal.none.BytecodeProviderImpl`

## Verification

To test the native-image build:

```bash
# Build native image
mvn clean package -Pnative

# Run the native executable
./target/atv
```

## Technical Details

### Hibernate Bytecode Enhancement Strategy

Native-image requires a different approach for Hibernate entity enhancement:

1. **JIT Mode (doesn't work in native-image)**:
   - Runtime bytecode manipulation via ByteBuddy
   - Dynamic proxy generation
   - Not compatible with AOT compilation

2. **AOT Mode (works in native-image)**:
   - Build-time bytecode enhancement via Maven plugin
   - Static proxy classes generated during compilation
   - Configured in pom.xml native profile with `hibernate-enhance-maven-plugin`

### Flyway Resource Scanning

Native-image bundles resources differently:
- Resources must be explicitly declared in `resource-config.json`
- Pattern matching registers files at build time
- Runtime scanning is disabled but unnecessary since all files are pre-registered

## References

- [Hibernate ORM Native Image Guide](https://hibernate.org/orm/documentation/)
- [GraalVM Native Image Compatibility Guide](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)
- [Spring Boot Native Image Support](https://docs.spring.io/spring-boot/reference/packaging/native-image/introducing-graalvm-native-images.html)
