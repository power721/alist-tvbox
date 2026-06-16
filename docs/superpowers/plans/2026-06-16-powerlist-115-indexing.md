# PowerList 115索引服务实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在PowerList实现115网盘文件离线索引服务，支持批量导入、高效搜索（bleve），为alist-tvbox提供HTTP API

**Architecture:** PowerList新增`internal/search/alist115`模块，独立bleve索引（与主索引隔离），复用existing 115存储配置，索引存储于`data/indexes/115/`

**Tech Stack:** Go 1.24, bleve v2, Gin, 已有PowerList基础设施

**项目路径:** `/home/harold/GolandProjects/PowerList`

---

## Task 1: 数据模型与路径映射

**Files:**
- Create: `/home/harold/GolandProjects/PowerList/internal/search/alist115/model.go`
- Create: `/home/harold/GolandProjects/PowerList/internal/search/alist115/path_mapper.go`
- Create: `/home/harold/GolandProjects/PowerList/internal/search/alist115/path_mapper_test.go`

- [ ] **Step 1: 创建目录**

```bash
cd /home/harold/GolandProjects/PowerList
mkdir -p internal/search/alist115
```

- [ ] **Step 2: 写测试 - 路径映射**

```go
// internal/search/alist115/path_mapper_test.go
package alist115

import (
	"testing"
)

func TestMapPath(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{"/🏷️我的115分享/电影/test.mkv", "/我的115分享/电影/test.mkv"},
		{"/我的115分享/电影/test.mkv", "/我的115分享/电影/test.mkv"},
		{"/other/path.mp4", "/other/path.mp4"},
	}
	
	for _, tt := range tests {
		result := MapPath(tt.input)
		if result != tt.expected {
			t.Errorf("MapPath(%q) = %q, want %q", tt.input, result, tt.expected)
		}
	}
}
```

- [ ] **Step 3: 运行测试验证失败**

```bash
go test ./internal/search/alist115 -v -run TestMapPath
```
Expected: FAIL (MapPath undefined)

- [ ] **Step 4: 实现路径映射**

```go
// internal/search/alist115/path_mapper.go
package alist115

import "strings"

// MapPath 映射路径，移除emoji前缀
func MapPath(path string) string {
	if strings.HasPrefix(path, "/🏷️我的115分享/") {
		return strings.Replace(path, "/🏷️我的115分享/", "/我的115分享/", 1)
	}
	return path
}
```

- [ ] **Step 5: 运行测试验证通过**

```bash
go test ./internal/search/alist115 -v -run TestMapPath
```
Expected: PASS

- [ ] **Step 6: 创建数据模型**

```go
// internal/search/alist115/model.go
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

- [ ] **Step 7: Commit**

```bash
git add internal/search/alist115/
git commit -m "feat(alist115): add data models and path mapper"
```

---

## Task 2: bleve索引服务核心

**Files:**
- Create: `/home/harold/GolandProjects/PowerList/internal/search/alist115/service.go`
- Create: `/home/harold/GolandProjects/PowerList/internal/search/alist115/service_test.go`

- [ ] **Step 1: 创建索引服务结构**

```go
// internal/search/alist115/service.go
package alist115

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	
	"github.com/blevesearch/bleve/v2"
	"github.com/google/uuid"
	log "github.com/sirupsen/logrus"
)

type Service struct {
	index     bleve.Index
	indexPath string
}

// NewService 创建索引服务
func NewService(dataDir string) (*Service, error) {
	indexPath := filepath.Join(dataDir, "indexes", "115")
	
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
	
	log.Infof("115 index service initialized at %s", indexPath)
	
	return &Service{
		index:     index,
		indexPath: indexPath,
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

- [ ] **Step 2: 实现批量导入**

```go
// BatchIndex 批量索引节点
func (s *Service) BatchIndex(nodes []IndexNode) (int, error) {
	if len(nodes) == 0 {
		return 0, nil
	}
	
	batch := s.index.NewBatch()
	indexed := 0
	
	for _, node := range nodes {
		// 应用路径映射
		node.Path = MapPath(node.Path)
		
		// 提取文件名
		name := filepath.Base(node.Path)
		isDir := strings.HasSuffix(node.Path, "/")
		
		doc := map[string]interface{}{
			"path":       node.Path,
			"name":       name,
			"size":       node.Size,
			"is_dir":     isDir,
			"indexed_at": node.IndexedAt.Unix(),
		}
		
		if err := batch.Index(uuid.NewString(), doc); err != nil {
			log.Errorf("batch index error: %v", err)
			continue
		}
		indexed++
	}
	
	if err := s.index.Batch(batch); err != nil {
		return 0, fmt.Errorf("execute batch: %w", err)
	}
	
	return indexed, nil
}
```

- [ ] **Step 3: 写测试**

```go
// internal/search/alist115/service_test.go
package alist115

import (
	"os"
	"testing"
	"time"
)

func TestServiceBatchIndex(t *testing.T) {
	// 创建临时目录
	tmpDir := t.TempDir()
	
	svc, err := NewService(tmpDir)
	if err != nil {
		t.Fatalf("NewService failed: %v", err)
	}
	defer svc.Close()
	
	// 准备测试数据
	nodes := []IndexNode{
		{Path: "/test/file1.mkv", Size: 1000, IndexedAt: time.Now()},
		{Path: "/🏷️我的115分享/file2.mp4", Size: 2000, IndexedAt: time.Now()},
	}
	
	indexed, err := svc.BatchIndex(nodes)
	if err != nil {
		t.Fatalf("BatchIndex failed: %v", err)
	}
	
	if indexed != 2 {
		t.Errorf("BatchIndex indexed %d, want 2", indexed)
	}
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
go test ./internal/search/alist115 -v -run TestServiceBatchIndex
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add internal/search/alist115/service.go internal/search/alist115/service_test.go
git commit -m "feat(alist115): implement bleve index service with batch import"
```

---

## File Structure

### PowerList新增文件
- **Create:** `internal/search/alist115/model.go` - 数据模型
- **Create:** `internal/search/alist115/service.go` - 索引服务核心
- **Create:** `internal/search/alist115/path_mapper.go` - 路径映射
- **Create:** `internal/search/alist115/service_test.go` - 单元测试
- **Modify:** `server/router.go` - 注册115 API路由
- **Create:** `server/handles/alist115.go` - HTTP处理器

---

## Task 3: 搜索功能实现

**Files:**
- Modify: `/home/harold/GolandProjects/PowerList/internal/search/alist115/service.go`

- [ ] **Step 1: 添加搜索方法到service.go**

在BatchIndex方法后添加Search方法（完整代码见规格文档）

- [ ] **Step 2: 添加Clear方法**

清空索引功能（完整代码见规格文档）

- [ ] **Step 3: 添加搜索测试**

在service_test.go添加TestServiceSearch（完整代码见规格文档）

- [ ] **Step 4: 运行测试**

```bash
go test ./internal/search/alist115 -v
```

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(alist115): add search and clear functionality"
```

---

## Task 4: HTTP API处理器

**Files:**
- Create: `/home/harold/GolandProjects/PowerList/server/handles/alist115.go`
- Modify: `/home/harold/GolandProjects/PowerList/server/router.go`

- [ ] **Step 1: 创建HTTP处理器**

```go
// server/handles/alist115.go
package handles

import (
	"github.com/OpenListTeam/OpenList/v4/internal/search/alist115"
	"github.com/gin-gonic/gin"
	"net/http"
)

var alist115Service *alist115.Service

// InitAlist115 初始化115索引服务
func InitAlist115(dataDir string) error {
	var err error
	alist115Service, err = alist115.NewService(dataDir)
	return err
}

// Alist115ImportBatch 批量导入
func Alist115ImportBatch(c *gin.Context) {
	var req alist115.ImportBatchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	indexed, err := alist115Service.BatchIndex(req.Nodes)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	
	c.JSON(http.StatusOK, alist115.ImportBatchResponse{
		Success: true,
		Indexed: indexed,
	})
}

// Alist115Search 搜索
func Alist115Search(c *gin.Context) {
	var req alist115.SearchRequest
	if err := c.ShouldBindQuery(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	result, err := alist115Service.Search(req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	
	c.JSON(http.StatusOK, result)
}

// Alist115Clear 清空索引
func Alist115Clear(c *gin.Context) {
	if err := alist115Service.Clear(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	
	c.JSON(http.StatusOK, gin.H{"success": true, "message": "Index cleared"})
}
```

- [ ] **Step 2: 注册路由到router.go**

在router.go的适当位置添加：

```go
// 115索引API
api.POST("/fs/115/import-batch", handles.Alist115ImportBatch)
api.GET("/fs/115/search", handles.Alist115Search)
api.DELETE("/fs/115/clear", handles.Alist115Clear)
```

- [ ] **Step 3: 在main.go初始化服务**

找到PowerList的main函数，在适当位置添加：

```go
if err := handles.InitAlist115(conf.Conf.DataDir); err != nil {
    log.Fatalf("init alist115 service failed: %v", err)
}
```

- [ ] **Step 4: 测试API**

启动PowerList，测试API：

```bash
# 测试导入
curl -X POST http://localhost:5244/api/fs/115/import-batch \
  -H "Content-Type: application/json" \
  -d '{"nodes":[{"path":"/test.mkv","size":123,"indexed_at":"2024-01-01T00:00:00Z"}]}'

# 测试搜索
curl "http://localhost:5244/api/fs/115/search?q=test"
```

- [ ] **Step 5: Commit**

```bash
git add server/handles/alist115.go
git commit -am "feat(alist115): add HTTP API handlers and routes"
```

---

## 验收标准

- [ ] 所有单元测试通过
- [ ] API端点响应正常
- [ ] 批量导入10k条记录 < 5秒
- [ ] 搜索响应 < 1秒
- [ ] 索引文件正确创建在 `data/indexes/115/`

---

计划完成。请选择执行方式：

**1. Subagent-Driven（推荐）** - 每个Task用独立subagent，review between tasks  
**2. Inline Execution** - 在当前会话执行，批量处理

