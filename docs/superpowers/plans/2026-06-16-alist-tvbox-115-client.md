# alist-tvbox 115索引客户端实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在alist-tvbox实现HTTP客户端，调用PowerList的115索引API（搜索功能为主）

**Architecture:** 最小实现 - 仅HTTP客户端调用PowerList搜索API，返回结果给前端

**Tech Stack:** Java 21, Spring Boot 3, RestTemplate/WebClient

**项目路径:** `/home/harold/workspace/alist-tvbox/.worktrees/115-client`

---

## 需求回顾

**方案B：最小实现**
- ✅ 搜索：调用PowerList API
- ❌ 刮削：由用户手动处理（不在此计划）
- ❌ 存储：不涉及（不在此计划）
- ✅ 播放：使用existing逻辑（通过PowerList `/d/路径`）

---

## Task 1: HTTP客户端实现

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/service/Index115Service.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/Index115SearchRequest.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/Index115SearchResponse.java`

### Steps:

- [ ] **Step 1: 创建DTO类**

```java
// dto/Index115SearchRequest.java
@Data
public class Index115SearchRequest {
    private String query;
    private Integer page = 1;
    private Integer perPage = 20;
    private Integer scope = 0; // 0=all, 1=folder, 2=file
}

// dto/Index115SearchResponse.java
@Data
public class Index115SearchResponse {
    @Data
    public static class Node {
        private String path;
        private String name;
        private Long size;
        private Boolean isDir;
        private Long indexedAt;
    }
    
    private List<Node> nodes;
    private Long total;
}
```

- [ ] **Step 2: 创建Service**

```java
// service/Index115Service.java
@Service
@Slf4j
public class Index115Service {
    
    @Value("${powerlist.url:http://localhost:5244}")
    private String powerListUrl;
    
    private final RestTemplate restTemplate;
    
    public Index115Service() {
        this.restTemplate = new RestTemplate();
    }
    
    public Index115SearchResponse search(Index115SearchRequest request) {
        String url = powerListUrl + "/api/fs/115/search";
        // 构建query params
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url)
            .queryParam("q", request.getQuery())
            .queryParam("page", request.getPage())
            .queryParam("per_page", request.getPerPage())
            .queryParam("scope", request.getScope());
        
        try {
            return restTemplate.getForObject(builder.toUriString(), Index115SearchResponse.class);
        } catch (Exception e) {
            log.error("Search 115 index failed: {}", e.getMessage());
            throw new RuntimeException("搜索失败: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: 配置application.yml**

```yaml
# 添加PowerList配置
powerlist:
  url: http://localhost:5244
```

- [ ] **Step 4: 编写单元测试**

```java
// service/Index115ServiceTest.java
@SpringBootTest
class Index115ServiceTest {
    
    @Autowired
    private Index115Service service;
    
    @Test
    void testSearch() {
        Index115SearchRequest request = new Index115SearchRequest();
        request.setQuery("侏罗纪");
        request.setPage(1);
        request.setPerPage(10);
        
        Index115SearchResponse response = service.search(request);
        
        assertNotNull(response);
        assertNotNull(response.getNodes());
        // Note: 如果PowerList未运行，测试会失败
    }
}
```

- [ ] **Step 5: 运行测试**

```bash
mvn test -Dtest=Index115ServiceTest
```

- [ ] **Step 6: Commit**

```bash
git add .
git commit -m "feat: add 115 index search client service"
```

---

## Task 2: REST API端点

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/web/Index115Controller.java`

### Steps:

- [ ] **Step 1: 创建Controller**

```java
// web/Index115Controller.java
@RestController
@RequestMapping("/api/115")
@Slf4j
public class Index115Controller {
    
    private final Index115Service index115Service;
    
    public Index115Controller(Index115Service index115Service) {
        this.index115Service = index115Service;
    }
    
    @GetMapping("/search")
    public Index115SearchResponse search(
        @RequestParam String q,
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(name = "per_page", defaultValue = "20") Integer perPage,
        @RequestParam(defaultValue = "0") Integer scope
    ) {
        Index115SearchRequest request = new Index115SearchRequest();
        request.setQuery(q);
        request.setPage(page);
        request.setPerPage(perPage);
        request.setScope(scope);
        
        return index115Service.search(request);
    }
}
```

- [ ] **Step 2: 测试API**

启动应用，测试：
```bash
curl "http://localhost:4567/api/115/search?q=侏罗纪&page=1&per_page=10"
```

- [ ] **Step 3: Commit**

```bash
git commit -am "feat: add 115 index search REST API endpoint"
```

---

## Task 3: 前端集成（可选）

**如果需要前端UI，创建搜索页面：**

- Create: `web-ui/src/views/Index115Search.vue`
- Modify: `web-ui/src/router/index.js` - 添加路由

**Steps:**

- [ ] **Step 1: 创建Vue组件**（简单示例）

```vue
<template>
  <div>
    <el-input v-model="query" placeholder="搜索115网盘" @keyup.enter="search" />
    <el-button @click="search">搜索</el-button>
    
    <el-table :data="results" style="margin-top: 20px">
      <el-table-column prop="name" label="文件名" />
      <el-table-column prop="size" label="大小" :formatter="formatSize" />
      <el-table-column label="操作">
        <template #default="{ row }">
          <el-button size="small" @click="play(row)">播放</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import axios from 'axios'

const query = ref('')
const results = ref([])

const search = async () => {
  const { data } = await axios.get('/api/115/search', {
    params: { q: query.value, page: 1, per_page: 20 }
  })
  results.value = data.nodes
}

const formatSize = (row) => {
  const size = row.size
  if (size < 1024) return size + 'B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(2) + 'KB'
  if (size < 1024 * 1024 * 1024) return (size / (1024 * 1024)).toFixed(2) + 'MB'
  return (size / (1024 * 1024 * 1024)).toFixed(2) + 'GB'
}

const play = (row) => {
  // 调用PowerList播放接口
  const playUrl = `http://localhost:5244/d${row.path}`
  window.open(playUrl, '_blank')
}
</script>
```

- [ ] **Step 2: 添加路由**

```js
// router/index.js
{
  path: '/115-search',
  name: 'Index115Search',
  component: () => import('@/views/Index115Search.vue')
}
```

- [ ] **Step 3: Commit**

```bash
git add web-ui/
git commit -m "feat: add 115 index search UI"
```

---

## 验收标准

- [ ] HTTP客户端正确调用PowerList API
- [ ] 搜索结果正确返回
- [ ] REST API端点响应正常
- [ ] （可选）前端UI可用

---

## 注意事项

1. **PowerList必须运行**：客户端依赖PowerList服务（localhost:5244）
2. **Docker配置**：如果PowerList在Docker内，确保网络互通
3. **错误处理**：PowerList宕机时给出友好提示
4. **播放链接**：搜索结果的path可直接拼接为 `http://powerlist:5244/d{path}`

---

计划完成。请选择执行方式：
- **Subagent-Driven**: 逐Task执行，review between tasks
- **Manual**: 手动实现
