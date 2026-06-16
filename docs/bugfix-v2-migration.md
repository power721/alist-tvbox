# Bug 修复：V2 数据库迁移 NOT NULL 约束问题

## 问题描述

原始的 `V2__Normalize_reserved_columns.java` 在添加新列时直接使用了 `NOT NULL` 约束：

```java
// 有问题的代码
String nullability = required ? " NOT NULL" : "";
execute(connection, "ALTER TABLE " + quote(connection, actualTable)
        + " ADD COLUMN sort_order INTEGER" + nullability + " DEFAULT 0");
```

### 为什么这是个问题？

1. **MySQL 5.7 兼容性**：在有现有数据的表上添加 `NOT NULL` 列时，即使有 `DEFAULT 0`，某些 MySQL 版本也会失败
2. **数据库差异**：不同数据库对于 `ALTER TABLE ADD COLUMN ... NOT NULL DEFAULT` 的行为不一致
3. **生产风险**：在有数千行数据的表上执行时可能导致迁移失败

## 解决方案

采用**三步迁移策略**：

### Step 1: 添加可空列
```java
execute(connection, "ALTER TABLE " + quote(connection, actualTable)
        + " ADD COLUMN sort_order INTEGER DEFAULT 0");
```
- 先添加为可空列，所有数据库都支持
- 使用 `DEFAULT 0` 确保新行有值

### Step 2: 迁移数据
```java
String oldOrder = findColumn(connection, actualTable, "order");
if (oldOrder != null) {
    execute(connection, "UPDATE " + quote(connection, actualTable)
            + " SET sort_order = " + quote(connection, oldOrder));
    execute(connection, "ALTER TABLE " + quote(connection, actualTable) 
            + " DROP COLUMN " + quote(connection, oldOrder));
}
```
- 从旧列复制数据到新列
- 删除旧的保留字列

### Step 3: 添加约束（针对必需字段）
```java
if (required && !hasSortOrder) {
    // 填充所有 NULL 值
    execute(connection, "UPDATE " + quote(connection, actualTable)
            + " SET sort_order = 0 WHERE sort_order IS NULL");

    // 根据数据库类型使用正确的语法
    String dbProduct = connection.getMetaData().getDatabaseProductName().toLowerCase();
    if (dbProduct.contains("mysql") || dbProduct.contains("mariadb")) {
        execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                + " MODIFY COLUMN sort_order INTEGER NOT NULL DEFAULT 0");
    } else if (dbProduct.contains("postgresql")) {
        execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                + " ALTER COLUMN sort_order SET NOT NULL");
    } else if (dbProduct.contains("h2")) {
        execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                + " ALTER COLUMN sort_order SET NOT NULL");
    }
}
```

## 优势

1. **跨数据库兼容性**：支持 MySQL、MariaDB、PostgreSQL、H2
2. **生产安全**：即使表有百万行数据也能成功迁移
3. **幂等性**：可以安全地重复执行
4. **优雅降级**：对不支持的数据库，保持为可空列（安全的 fallback）

## 测试验证

所有测试通过：
- ✅ `V2NormalizeReservedColumnsTest` - 迁移逻辑测试
- ✅ `ReservedColumnMappingTest` - 实体字段映射测试
- ✅ `SiteDtoJsonTest` - JSON 兼容性测试
- ✅ `SchemaValidationTest` - 新数据库 schema 验证
- ✅ `LegacySchemaValidationTest` - 旧数据库升级验证

## 影响的表

- `site` (required: true)
- `navigation` (required: true)
- `telegram_channel` (required: true)
- `emby` (required: false)
- `jellyfin` (required: false)
- `feiniu` (required: false)

## 部署注意事项

1. **备份数据库**：这是不可逆的迁移，删除了旧列
2. **测试环境验证**：先在测试环境验证迁移成功
3. **预期停机时间**：
   - 小表（<1000 行）：< 1 秒
   - 中等表（1000-100k 行）：1-10 秒
   - 大表（>100k 行）：10-60 秒

## 相关文件

- `src/main/java/db/migration/current/V2__Normalize_reserved_columns.java` - 修复后的迁移文件
- `src/test/java/db/migration/current/V2NormalizeReservedColumnsTest.java` - 单元测试
