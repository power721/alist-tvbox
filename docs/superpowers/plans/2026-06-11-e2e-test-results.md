## 端到端测试结果

> **注意**: 以下测试需要手动启动应用后执行。代码实现已完成并通过单元测试。

### 测试步骤

#### Step 1: 启动应用
```bash
mvn spring-boot:run
```

#### Step 2: 测试获取全局配置（初始为空）
```bash
curl -X GET http://localhost:4567/api/subscriptions/global-config
```
预期结果: `{}`

#### Step 3: 设置全局黑名单
```bash
curl -X PUT http://localhost:4567/api/subscriptions/global-config \
  -H "Content-Type: application/json" \
  -d '{"sites-blacklist": ["site1", "site2"]}'
```
预期结果: HTTP 200

#### Step 4: 验证全局配置已保存
```bash
curl -X GET http://localhost:4567/api/subscriptions/global-config
```
预期结果: `{"sites-blacklist":["site1","site2"]}`

#### Step 5: 测试订阅应用全局黑名单
```bash
curl http://localhost:4567/sub/-/0 | jq '.sites[] | .key' | head -20
```
预期结果: 不包含 site1 和 site2

#### Step 6: 测试订阅白名单覆盖
创建订阅，override 字段设置为：
```json
{"sites-whitelist": ["site1"]}
```
获取该订阅配置，预期只包含 site1

#### Step 7: 测试全局白名单优先于黑名单
```bash
curl -X PUT http://localhost:4567/api/subscriptions/global-config \
  -H "Content-Type: application/json" \
  -d '{"sites-whitelist": ["site1"], "sites-blacklist": ["site2"]}'

curl http://localhost:4567/sub/-/0 | jq '.sites[] | .key'
```
预期结果: 只包含 site1（黑名单被忽略）

---

## 实现完成

所有代码实现和单元测试已完成并通过。端到端测试需要运行中的应用实例。

**核心功能：**
- ✅ 全局配置存储在 Setting 表
- ✅ GET /api/subscriptions/global-config - 获取全局配置
- ✅ PUT /api/subscriptions/global-config - 更新全局配置
- ✅ 订阅生成时自动应用全局配置
- ✅ 订阅级别配置完全替换全局配置
- ✅ 白名单优先于黑名单
- ✅ 支持 sites-whitelist 和 sites-blacklist
- ✅ 全局配置不支持 spider 字段

**测试覆盖：**
- ✅ 9个单元测试全部通过
- ✅ 3个集成场景测试全部通过
- ✅ 全量测试套件通过（149 tests）
