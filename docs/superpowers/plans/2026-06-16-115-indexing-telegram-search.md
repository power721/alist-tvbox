# 115网盘离线索引系统实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在telegram-search中实现115网盘文件离线索引服务，支持批量导入、增量更新、高效搜索（bleve），为alist-tvbox提供HTTP API

**Architecture:** Go服务新增`internal/alist115`模块，使用bleve全文索引，复用existing external API框架，索引存储于`data/indexes/115/`，支持webdavsim格式兼容

**Tech Stack:** Go 1.25, bleve v2, Gin, SQLite (metadata), XZ压缩支持

---

## File Structure

### telegram-search (Go)
- **Create:** `internal/alist115/model.go` - 数据模型定义
- **Create:** `internal/alist115/service.go` - 索引服务核心逻辑
- **Create:** `internal/alist115/importer.go` - 批量导入处理
- **Create:** `internal/alist115/searcher.go` - bleve搜索封装
- **Create:** `internal/alist115/path_mapper.go` - 路径映射（emoji处理）
- **Create:** `internal/alist115/service_test.go` - 单元测试
- **Modify:** `internal/api/router.go` - 注册115 API路由
- **Create:** `internal/api/alist115_handlers.go` - HTTP处理器
- **Modify:** `go.mod` - 添加bleve依赖（如未有）
- **Create:** `internal/config/alist115.go` - 配置结构

### alist-tvbox (Java)
- **Create:** `src/main/java/cn/har01d/alist_tvbox/service/Index115Client.java` - HTTP客户端
- **Create:** `src/main/java/cn/har01d/alist_tvbox/dto/Index115SearchRequest.java` - 请求DTO
- **Create:** `src/main/java/cn/har01d/alist_tvbox/dto/Index115SearchResponse.java` - 响应DTO
- **Modify:** `src/main/resources/application.yaml` - telegram-search配置

---

## Phase 1: telegram-search核心模块（Go）

### Task 1: 数据模型与配置

**Files:**
- Create: `/home/harold/workspace/telegram-search/internal/alist115/model.go`
- Create: `/home/harold/workspace/telegram-search/internal/config/alist115.go`

- [ ] **Step 1: 创建数据模型**

```go
// internal/alist115/model.go
package alist115

import "time"

// IndexNode 索引节点
type IndexNode struct {
    Path      string    `json:"path"`
    Size      int64     `json:"size"`
    IndexedAt time.Time `json:"indexed_at"`
}

// ImportBatchRequest 批量导入请求
type ImportBatchRequest struct {
    Nodes []IndexNode `json:"nodes" binding:"required"`
}

// ImportBatchResponse 批量导入响应
type ImportBatchResponse struct {
    Success bool   `json:"success"`
    Indexed int    `json:"indexed"`
    Message string `json:"message,omitempty"`
}

// SearchRequest 搜索请求
type SearchRequest struct {
    Query   string `form:"q" binding:"required"`
    Page    int    `form:"page"`
    PerPage int    `form:"per_page"`
    Scope   int    `form:"scope"` // 0=all, 1=folder, 2=file
}

// SearchResponse 搜索响应
type SearchResponse struct {
    Nodes []SearchNode `json:"nodes"`
    Total int64        `json:"total"`
}

// SearchNode 搜索结果节点
type SearchNode struct {
    Path      string    `json:"path"`
    Name      string    `json:"name"`
    Size      int64     `json:"size"`
    IsDir     bool      `json:"is_dir"`
    IndexedAt time.Time `json:"indexed_at"`
}
```

- [ ] **Step 2: 创建配置结构**

```go
// internal/config/alist115.go
package config

type Alist115Config struct {
    Enabled   bool   `yaml:"enabled"`
    IndexPath string `yaml:"index_path"`
}

// 默认配置
var DefaultAlist115Config = Alist115Config{
    Enabled:   true,
    IndexPath: "data/indexes/115",
}
```

- [ ] **Step 3: Commit**

```bash
cd /home/harold/workspace/telegram-search
git add internal/alist115/model.go internal/config/alist115.go
git commit -m "feat(alist115): add data models and config"
```

### Task 2: 路径映射工具

**Files:**
- Create: `/home/harold/workspace/telegram-search/internal/alist115/path_mapper.go`
- Create: `/home/harold/workspace/telegram-search/internal/alist115/path_mapper_test.go`

- [ ] **Step 1: 写测试 - emoji前缀移除**

```go
// internal/alist115/path_mapper_test.go
package alist115

import (
    "testing"
    "github.com/stretchr/testify/assert"
)

func TestMapPath(t *testing.T) {
    tests := []struct {
        input    string
        expected string
    }{
        {"/🏷️我的115分享/电影/test.mkv", "/我的115分享/电影/test.mkv"},
        {"/我的115分享/电影/test.mkv", "/我的115分享/电影/test.mkv"},
        {"/other/path.mp4", "/other/path.mp4"},
        {"/🏷️我的115分享/", "/我的115分享/"},
    }
    
    for _, tt := range tests {
        result := MapPath(tt.input)
        assert.Equal(t, tt.expected, result, "MapPath(%q)", tt.input)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd /home/harold/workspace/telegram-search
go test ./internal/alist115 -v -run TestMapPath
```
Expected: FAIL (MapPath undefined)

- [ ] **Step 3: 实现路径映射**

```go
// internal/alist115/path_mapper.go
package alist115

import "strings"

// MapPath 映射路径，移除emoji前缀
func MapPath(path string) string {
    // 替换emoji前缀
    if strings.HasPrefix(path, "/🏷️我的115分享/") {
        return strings.Replace(path, "/🏷️我的115分享/", "/我的115分享/", 1)
    }
    return path
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
go test ./internal/alist115 -v -run TestMapPath
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add internal/alist115/path_mapper.go internal/alist115/path_mapper_test.go
git commit -m "feat(alist115): add path mapper for emoji prefix"
```

### Task 3: bleve索引服务核心

**Files:**
- Create: `/home/harold/workspace/telegram-search/internal/alist115/service.go`
- Modify: `/home/harold/workspace/telegram-search/go.mod`

- [ ] **Step 1: 添加bleve依赖**

```bash
cd /home/harold/workspace/telegram-search
go get github.com/blevesearch/bleve/v2@latest
```

- [ ] **Step 2: 创建索引服务**

```go
// internal/alist115/service.go
package alist115

import (
    "context"
    "fmt"
    "os"
    "path/filepath"
    "strings"
    
    "github.com/blevesearch/bleve/v2"
    "go.uber.org/zap"
)

type Service struct {
    index  bleve.Index
    logger *zap.Logger
}

// NewService 创建索引服务
func NewService(indexPath string, logger *zap.Logger) (*Service, error) {
    // 确保目录存在
    if err := os.MkdirAll(indexPath, 0755); err != nil {
        return nil, fmt.Errorf("create index dir: %w", err)
    }
    
    // 创建索引映射
    mapping := bleve.NewIndexMapping()
    
    // 打开或创建索引
    index, err := bleve.Open(indexPath)
    if err == bleve.ErrorIndexPathDoesNotExist {
        index, err = bleve.New(indexPath, mapping)
    }
    if err != nil {
        return nil, fmt.Errorf("open/create index: %w", err)
    }
    
    return &Service{
        index:  index,
        logger: logger,
    }, nil
}

// Close 关闭索引
func (s *Service) Close() error {
    if s.index != nil {
        return s.index.Close()
    }
    return nil
}
```

// __CONTINUE_HERE__
