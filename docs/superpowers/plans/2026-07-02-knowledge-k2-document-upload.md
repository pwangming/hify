# knowledge K2 文档上传与分段 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 上传 txt/md 文档 → 同步提取文本、固定长度分段、落库（kb_document 存原始文件 bytea + kb_chunk 存分段）+ 前端知识库详情页（文档列表/上传/分段预览抽屉）。

**Architecture:** 照 knowledge K1 的三层模式。新增 `TextChunker` 纯函数分段器、`DocumentService`/`DocumentController`；`DatasetService.delete` 升级为级联软删。kb_chunk **不带 embedding 列**（K3 迁移补 vector(1024)）。Spec：`docs/superpowers/specs/2026-07-02-knowledge-k2-document-upload-design.md`。

**Tech Stack:** Spring Boot 3 + MyBatis-Plus 3.5.12（`Db.saveBatch` 批量写）+ Flyway；Vue 3 + Element Plus（el-upload/el-drawer）+ vitest。

## Global Constraints

- 后端命令在 `/home/wang/playlab/hify/server` 下跑，前端在 `/home/wang/playlab/hify/web` 下跑；mvn 不带 `-q`。
- TDD 先红后绿；每步测试证据入报告。
- 错误码：**仅新增 15004、15001 两个**（KnowledgeError 枚举）；其余复用 CommonError。15004 的号是 api-standards 预定义，不可改。
- 不引新依赖（Tika 明确排除）；不改 SecurityConfig；只新增 V14 迁移。
- kb_chunk 无 embedding 列；不建任何向量索引。
- 分段参数全局配置：`hify.knowledge.chunk-size: 500` / `chunk-overlap: 50`；实际参数记录在 kb_document 行。
- 同步处理：提取+分段是纯内存操作；`@Transactional` 内零外部 IO。
- 批量写分段用 `Db.saveBatch(chunks, 1000)`（database-standards §2.1）；连接串补 `reWriteBatchedInserts=true`。
- 列表查询禁 select bytea 大列：文档分页必须排除 `content` 列。
- Long 序列化为 string（前端 fileSize 是 string；Integer 如 chunkCount/position 仍是 number）。
- 前端类型命名用 `KbDocument`（不能叫 `Document`，与 DOM 内置类型撞名）。
- 删除语义：库→文档→分段级联**软删**；删除幂等。

---

### Task 1: V14 迁移 + 实体×2 + Mapper×2 + 配置

**Files:**
- Create: `server/src/main/resources/db/migration/V14__create_kb_document_chunk.sql`
- Create: `server/src/main/java/com/hify/knowledge/entity/KbDocument.java`
- Create: `server/src/main/java/com/hify/knowledge/entity/KbChunk.java`
- Create: `server/src/main/java/com/hify/knowledge/mapper/KbDocumentMapper.java`
- Create: `server/src/main/java/com/hify/knowledge/mapper/KbChunkMapper.java`
- Modify: `server/src/main/resources/application.yml`（三处：datasource url、spring.servlet.multipart、hify.knowledge）

**Interfaces:**
- Consumes: `com.hify.common.BaseEntity`。
- Produces: `KbDocument`（getDatasetId/getName/getFileType/getFileSize/getContent/getStatus/getChunkCount/getChunkSize/getChunkOverlap + setter + 基类）、`KbChunk`（getDocumentId/getDatasetId/getPosition/getContent + setter）、`KbDocumentMapper extends BaseMapper<KbDocument>`、`KbChunkMapper extends BaseMapper<KbChunk>`；配置键 `hify.knowledge.chunk-size` / `hify.knowledge.chunk-overlap`。

- [x] **Step 1: 写迁移脚本**

`server/src/main/resources/db/migration/V14__create_kb_document_chunk.sql`：

```sql
-- V14：文档与分段表（knowledge 模块）。原始文件存 bytea（架构拍板：文件不是独立实体，随库备份）。
-- 模块内 FK 允许建（data-model 第 3 条）；FK 不带 on delete cascade——删除走软删，物理级联永不触发，FK 只作引用完整性兜底。
-- kb_chunk 无 embedding 向量列：K3 做向量化时用新迁移补 vector(1024) 列 + HNSW 索引（K2 spec 决策 1）。

create table kb_document (
    id            bigint      generated always as identity primary key,
    dataset_id    bigint      not null references dataset(id),
    name          text        not null check (char_length(name) <= 200),
    file_type     text        not null check (file_type in ('txt', 'md')),
    file_size     bigint      not null,
    content       bytea       not null,
    -- 默认 pending（安全侧：漏赋值时宁可显示待处理）；K2 同步流程插入时显式写 ready
    status        text        not null default 'pending'
                  check (status in ('pending', 'processing', 'ready', 'failed')),
    chunk_count   int         not null default 0,
    chunk_size    int         not null,
    chunk_overlap int         not null,
    deleted       boolean     not null default false,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now()
);
comment on table kb_document is '知识库文档（knowledge 模块）：元数据+原始文件 bytea；status 四态为 K3 异步向量化预留；chunk_size/overlap 记录分段实际参数';
create index kb_document_dataset_idx on kb_document (dataset_id);

create table kb_chunk (
    id          bigint      generated always as identity primary key,
    document_id bigint      not null references kb_document(id),
    dataset_id  bigint      not null,
    position    int         not null,
    content     text        not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table kb_chunk is '文档分段（knowledge 模块）：dataset_id 为 K4 检索用冗余列；embedding 向量列 K3 迁移补加';
create index kb_chunk_document_idx on kb_chunk (document_id);
create index kb_chunk_dataset_idx on kb_chunk (dataset_id);
```

- [x] **Step 2: 写实体**

`server/src/main/java/com/hify/knowledge/entity/KbDocument.java`：

```java
package com.hify.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 文档表 {@code kb_document} 映射实体。继承 BaseEntity（id/createTime/updateTime/deleted）。
 * content 是原始文件 bytea；列表查询必须显式排除本列（database-standards：大列不进列表）。
 */
@TableName("kb_document")
public class KbDocument extends BaseEntity {

    private Long datasetId;
    private String name;
    private String fileType;
    private Long fileSize;
    private byte[] content;
    private String status;
    private Integer chunkCount;
    private Integer chunkSize;
    private Integer chunkOverlap;

    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }

    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }

    public Integer getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(Integer chunkOverlap) { this.chunkOverlap = chunkOverlap; }
}
```

`server/src/main/java/com/hify/knowledge/entity/KbChunk.java`：

```java
package com.hify.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 分段表 {@code kb_chunk} 映射实体。dataset_id 为检索用冗余列；embedding 列 K3 迁移补加。
 */
@TableName("kb_chunk")
public class KbChunk extends BaseEntity {

    private Long documentId;
    private Long datasetId;
    private Integer position;
    private String content;

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
```

- [x] **Step 3: 写两个 Mapper**

`server/src/main/java/com/hify/knowledge/mapper/KbDocumentMapper.java`：

```java
package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;

/** kb_document 表访问。K2 纯框架 CRUD，无手写 SQL。 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
}
```

`server/src/main/java/com/hify/knowledge/mapper/KbChunkMapper.java`：

```java
package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;

/** kb_chunk 表访问。批量写走 Db.saveBatch（database-standards §2.1）。 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {
}
```

- [x] **Step 4: 改 application.yml 三处**

4a. datasource url 补 `reWriteBatchedInserts=true`（database-standards §2.1，让 JDBC 批量插入重写成多值 insert）。原：

```yaml
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/hify}
```

改为：

```yaml
    # reWriteBatchedInserts：批量插入重写为多值 insert，chunk 批量写入快一个量级（database-standards §2.1）
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/hify?reWriteBatchedInserts=true}
```

4b. `spring:` 下（与 datasource 平级）新增 multipart 配置（与 nginx client_max_body_size 对齐，api-standards §6）：

```yaml
  # 文件上传限制（api-standards §6：单文件 ≤50MB，与 nginx client_max_body_size 对齐）。
  # 超限由 GlobalExceptionHandler 转 10001。
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB
```

4c. `hify:` 下新增 knowledge 段（放 conversation 段之前，按模块字母序就近即可）：

```yaml
  knowledge:
    # 固定长度分段参数：每段字符数 / 相邻段重叠字符数（K2 spec 决策 4：全局默认，不做前端可调）。
    # 实际使用的参数会记录在 kb_document 行上，为将来重分段留底。改分段口径只动这里。
    chunk-size: ${HIFY_KNOWLEDGE_CHUNK_SIZE:500}
    chunk-overlap: ${HIFY_KNOWLEDGE_CHUNK_OVERLAP:50}
```

- [x] **Step 5: 编译 + 既有测试回归**

```bash
cd /home/wang/playlab/hify/server && mvn test
```

Expected: `BUILD SUCCESS`，`Tests run: 309, Failures: 0, Errors: 0`（与 main 基线一致）。

- [x] **Step 6: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/resources/db/migration/V14__create_kb_document_chunk.sql server/src/main/java/com/hify/knowledge/entity server/src/main/java/com/hify/knowledge/mapper server/src/main/resources/application.yml && git commit -m "feat(knowledge): V14 kb_document/kb_chunk 表 + 实体 + Mapper + 上传/分段配置（K2）"
```

---

### Task 2: TextChunker 分段器（TDD）

**Files:**
- Create: `server/src/main/java/com/hify/knowledge/service/TextChunker.java`
- Test: `server/src/test/java/com/hify/knowledge/service/TextChunkerTest.java`

**Interfaces:**
- Consumes: 无（纯函数，零依赖）。
- Produces: `static List<String> TextChunker.split(String text, int chunkSize, int overlap)`——Task 3 的 DocumentService 调用。语义：滑动窗口切分（步长 = chunkSize − overlap），每段 trim，空段跳过；`chunkSize <= 0 || overlap < 0 || overlap >= chunkSize` 抛 IllegalArgumentException；text 为 null/空白返回空列表。

- [x] **Step 1: 写失败测试**

`server/src/test/java/com/hify/knowledge/service/TextChunkerTest.java`：

```java
package com.hify.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    @Test
    void 长文按窗口切分_步长等于size减overlap() {
        // 1200 字符，size=500 overlap=50 → 起点 0/450/900 → 3 段
        String text = "a".repeat(1200);
        List<String> chunks = TextChunker.split(text, 500, 50);
        assertEquals(3, chunks.size());
        assertEquals(500, chunks.get(0).length());
        assertEquals(500, chunks.get(1).length());
        assertEquals(300, chunks.get(2).length()); // 900..1200
    }

    @Test
    void 相邻段共享overlap字符() {
        // 用可辨识内容验证重叠：第 2 段开头 = 第 1 段末尾 overlap 个字符
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            sb.append((char) ('A' + i % 26));
        }
        List<String> chunks = TextChunker.split(sb.toString(), 100, 20);
        String tailOfFirst = chunks.get(0).substring(80); // 第 1 段最后 20 字符
        assertTrue(chunks.get(1).startsWith(tailOfFirst));
    }

    @Test
    void 文本短于一段_整体一段() {
        List<String> chunks = TextChunker.split("短文本", 500, 50);
        assertEquals(List.of("短文本"), chunks);
    }

    @Test
    void 段内容会trim首尾空白() {
        String text = "  hello  ";
        List<String> chunks = TextChunker.split(text, 500, 50);
        assertEquals(List.of("hello"), chunks);
    }

    @Test
    void 全空白文本_返回空列表() {
        assertEquals(List.of(), TextChunker.split("   \n\t  ", 500, 50));
    }

    @Test
    void null文本_返回空列表() {
        assertEquals(List.of(), TextChunker.split(null, 500, 50));
    }

    @Test
    void 纯空白的窗口段被跳过_不产生空段() {
        // 100 个空格 + 内容：第一窗口全空白应被跳过
        String text = " ".repeat(100) + "content";
        List<String> chunks = TextChunker.split(text, 100, 0);
        assertEquals(List.of("content"), chunks);
    }

    @Test
    void 参数非法_抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> TextChunker.split("x", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TextChunker.split("x", 500, -1));
        assertThrows(IllegalArgumentException.class, () -> TextChunker.split("x", 500, 500));
    }
}
```

- [x] **Step 2: 跑测试确认编译失败（红）**

```bash
cd /home/wang/playlab/hify/server && mvn test -Dtest=TextChunkerTest
```

Expected: `COMPILATION ERROR`（TextChunker 不存在）。

- [x] **Step 3: 写实现**

`server/src/main/java/com/hify/knowledge/service/TextChunker.java`：

```java
package com.hify.knowledge.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定长度分段器（K2 spec §2.4）：按字符滑动窗口切分，步长 = chunkSize − overlap，
 * 相邻段共享 overlap 个字符（避免语义被拦腰切断后两边都检索不到）。
 * 纯函数、零依赖，不感知 Spring/DB。
 */
public final class TextChunker {

    private TextChunker() {
    }

    public static List<String> split(String text, int chunkSize, int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("要求 chunkSize > 0 且 0 <= overlap < chunkSize");
        }
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int step = chunkSize - overlap;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + chunkSize, text.length());
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end == text.length()) {
                break; // 已到文末，避免因 overlap 回退产生重复尾段
            }
        }
        return chunks;
    }
}
```

- [x] **Step 4: 跑测试确认全绿**

```bash
cd /home/wang/playlab/hify/server && mvn test -Dtest=TextChunkerTest
```

Expected: `Tests run: 8, Failures: 0, Errors: 0`。

- [x] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/java/com/hify/knowledge/service/TextChunker.java server/src/test/java/com/hify/knowledge/service/TextChunkerTest.java && git commit -m "feat(knowledge): TextChunker 固定长度分段器（TDD）"
```

---

### Task 3: KnowledgeError + DTO + DocumentService + DatasetService 级联（TDD）

**Files:**
- Create: `server/src/main/java/com/hify/knowledge/constant/KnowledgeError.java`
- Create: `server/src/main/java/com/hify/knowledge/dto/DocumentResponse.java`
- Create: `server/src/main/java/com/hify/knowledge/dto/ChunkResponse.java`
- Create: `server/src/main/java/com/hify/knowledge/service/DocumentService.java`
- Modify: `server/src/main/java/com/hify/knowledge/service/DatasetService.java`（delete 级联 + assertCanModify 改包级 static + 构造函数加两个 mapper）
- Test: `server/src/test/java/com/hify/knowledge/service/DocumentServiceTest.java`
- Modify: `server/src/test/java/com/hify/knowledge/service/DatasetServiceTest.java`（构造更新 + 级联用例）

**Interfaces:**
- Consumes: Task 1 的实体与 Mapper；Task 2 的 `TextChunker.split`；`Db.saveBatch(List, int)`（com.baomidou.mybatisplus.extension.toolkit.Db，测试用 `mockStatic(Db.class)`）。
- Produces（Task 4 的 Controller 依赖）:
  - `DocumentResponse upload(Long datasetId, MultipartFile file, CurrentUser current)`
  - `PageResult<DocumentResponse> pageDocuments(Long datasetId, int page, int size)`
  - `void deleteDocument(Long id, CurrentUser current)`
  - `PageResult<ChunkResponse> pageChunks(Long documentId, int page, int size)`
  - `record DocumentResponse(Long id, Long datasetId, String name, String fileType, Long fileSize, String status, Integer chunkCount, OffsetDateTime createTime, OffsetDateTime updateTime)`
  - `record ChunkResponse(Long id, Integer position, String content)`
  - `KnowledgeError.DOCUMENT_FORMAT_UNSUPPORTED(15004)` / `DOCUMENT_CONTENT_EMPTY(15001)`

- [x] **Step 1: 写失败测试**

`server/src/test/java/com/hify/knowledge/service/DocumentServiceTest.java`：

```java
package com.hify.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.constant.KnowledgeError;
import com.hify.knowledge.dto.DocumentResponse;
import com.hify.knowledge.entity.Dataset;
import com.hify.knowledge.entity.KbChunk;
import com.hify.knowledge.entity.KbDocument;
import com.hify.knowledge.mapper.DatasetMapper;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.knowledge.mapper.KbDocumentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentServiceTest {

    private DatasetMapper datasetMapper;
    private KbDocumentMapper documentMapper;
    private KbChunkMapper chunkMapper;
    private DocumentService service;
    private MockedStatic<Db> dbMock;

    private final CurrentUser owner = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);
    private final CurrentUser other = new CurrentUser(8L, "carol", CurrentUser.ROLE_MEMBER);

    @BeforeEach
    void setUp() {
        datasetMapper = mock(DatasetMapper.class);
        documentMapper = mock(KbDocumentMapper.class);
        chunkMapper = mock(KbChunkMapper.class);
        // chunkSize=100 / overlap=10：测试用小参数，切分结果好断言
        service = new DocumentService(datasetMapper, documentMapper, chunkMapper, 100, 10);
        dbMock = mockStatic(Db.class);
        dbMock.when(() -> Db.saveBatch(anyList(), anyInt())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        dbMock.close();
    }

    /** bob(7) 拥有的知识库。 */
    private Dataset ownedDataset() {
        Dataset d = new Dataset();
        d.setId(10L);
        d.setName("客服知识库");
        d.setOwnerId(7L);
        return d;
    }

    private KbDocument doc10() {
        KbDocument doc = new KbDocument();
        doc.setId(20L);
        doc.setDatasetId(10L);
        doc.setName("faq.txt");
        doc.setFileType("txt");
        doc.setFileSize(11L);
        doc.setStatus("ready");
        doc.setChunkCount(1);
        return doc;
    }

    private MockMultipartFile txt(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 上传_成功_文档字段落库且分段批量写入() {
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        String content = "x".repeat(250); // size=100 overlap=10 步长90 → 0/90/180 → 3 段
        ArgumentCaptor<KbDocument> docCaptor = ArgumentCaptor.forClass(KbDocument.class);

        DocumentResponse resp = service.upload(10L, txt("faq.txt", content), owner);

        verify(documentMapper).insert(docCaptor.capture());
        KbDocument saved = docCaptor.getValue();
        assertEquals(10L, saved.getDatasetId());
        assertEquals("faq.txt", saved.getName());
        assertEquals("txt", saved.getFileType());
        assertEquals("ready", saved.getStatus());
        assertEquals(3, saved.getChunkCount());
        assertEquals(100, saved.getChunkSize());   // 实际参数记录在行上
        assertEquals(10, saved.getChunkOverlap());
        assertEquals(3, resp.chunkCount());
        dbMock.verify(() -> Db.saveBatch(anyList(), eq(1000)));
    }

    @Test
    void 上传_分段带document和dataset冗余id_position从1起() {
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        // insert 后回填 id=20（模拟 MyBatis-Plus 主键回填）
        when(documentMapper.insert(any(KbDocument.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbDocument.class).setId(20L);
            return 1;
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KbChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);

        service.upload(10L, txt("faq.txt", "y".repeat(150)), owner); // → 2 段

        dbMock.verify(() -> Db.saveBatch(chunksCaptor.capture(), eq(1000)));
        List<KbChunk> chunks = chunksCaptor.getValue();
        assertEquals(2, chunks.size());
        assertEquals(20L, chunks.get(0).getDocumentId());
        assertEquals(10L, chunks.get(0).getDatasetId());
        assertEquals(1, chunks.get(0).getPosition());
        assertEquals(2, chunks.get(1).getPosition());
    }

    @Test
    void 上传_md扩展名_放行且fileType为md() {
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        ArgumentCaptor<KbDocument> captor = ArgumentCaptor.forClass(KbDocument.class);
        service.upload(10L, txt("README.MD", "hello markdown"), owner); // 大小写不敏感
        verify(documentMapper).insert(captor.capture());
        assertEquals("md", captor.getValue().getFileType());
    }

    @Test
    void 上传_库不存在_NOT_FOUND() {
        when(datasetMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> service.upload(99L, txt("a.txt", "hi"), owner));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 上传_非owner非admin_FORBIDDEN() {
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        BizException ex = assertThrows(BizException.class,
                () -> service.upload(10L, txt("a.txt", "hi"), other));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
        verify(documentMapper, never()).insert(any(KbDocument.class));
    }

    @Test
    void 上传_扩展名不支持_15004() {
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        BizException ex = assertThrows(BizException.class,
                () -> service.upload(10L, txt("report.pdf", "hi"), owner));
        assertEquals(KnowledgeError.DOCUMENT_FORMAT_UNSUPPORTED, ex.errorCode());
    }

    @Test
    void 上传_内容全空白_15001() {
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        BizException ex = assertThrows(BizException.class,
                () -> service.upload(10L, txt("blank.txt", "   \n\t "), owner));
        assertEquals(KnowledgeError.DOCUMENT_CONTENT_EMPTY, ex.errorCode());
        verify(documentMapper, never()).insert(any(KbDocument.class));
    }

    @Test
    void 上传_文件名超200字符_PARAM_INVALID() {
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        String longName = "n".repeat(201) + ".txt";
        BizException ex = assertThrows(BizException.class,
                () -> service.upload(10L, txt(longName, "hi"), owner));
        assertEquals(CommonError.PARAM_INVALID, ex.errorCode());
    }

    @Test
    void 文档分页_库不存在_NOT_FOUND() {
        when(datasetMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.pageDocuments(99L, 1, 20));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 文档分页_返回PageResult() {
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        Page<KbDocument> page = Page.of(1, 20);
        page.setRecords(List.of(doc10()));
        page.setTotal(1);
        when(documentMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.pageDocuments(10L, 1, 20);

        assertEquals(1, result.total());
        assertEquals("faq.txt", result.list().get(0).name());
    }

    @Test
    void 删除文档_不存在_幂等不抛() {
        when(documentMapper.selectById(99L)).thenReturn(null);
        service.deleteDocument(99L, owner);
        verify(documentMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void 删除文档_非owner非admin_FORBIDDEN() {
        when(documentMapper.selectById(20L)).thenReturn(doc10());
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        BizException ex = assertThrows(BizException.class, () -> service.deleteDocument(20L, other));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
    }

    @Test
    void 删除文档_owner_级联软删文档与分段() {
        when(documentMapper.selectById(20L)).thenReturn(doc10());
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        service.deleteDocument(20L, owner);
        verify(documentMapper).deleteById(20L);
        verify(chunkMapper).delete(any()); // @TableLogic 使 delete = update set deleted=true
    }

    @Test
    void 分段分页_文档不存在_NOT_FOUND() {
        when(documentMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.pageChunks(99L, 1, 20));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 分段分页_返回position正序内容() {
        when(documentMapper.selectById(20L)).thenReturn(doc10());
        KbChunk c = new KbChunk();
        c.setId(30L);
        c.setPosition(1);
        c.setContent("第一段");
        Page<KbChunk> page = Page.of(1, 20);
        page.setRecords(List.of(c));
        page.setTotal(1);
        when(chunkMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.pageChunks(20L, 1, 20);

        assertEquals(1, result.list().get(0).position());
        assertEquals("第一段", result.list().get(0).content());
    }

    @Test
    void 分页守卫_size超100_PARAM_INVALID() {
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        BizException ex = assertThrows(BizException.class, () -> service.pageDocuments(10L, 1, 101));
        assertEquals(CommonError.PARAM_INVALID, ex.errorCode());
    }
}
```

在 `DatasetServiceTest.java` 中追加级联用例并更新构造（`setUp` 中 `new DatasetService(mapper)` 改为三 mapper 版本）：

```java
    // setUp 增加：
    // documentMapper = mock(KbDocumentMapper.class);
    // chunkMapper = mock(KbChunkMapper.class);
    // service = new DatasetService(mapper, documentMapper, chunkMapper);

    @Test
    void 删除_级联软删文档与分段() {
        when(mapper.selectById(10L)).thenReturn(owned());
        service.delete(10L, owner);
        verify(mapper).deleteById(10L);
        verify(documentMapper).delete(any());
        verify(chunkMapper).delete(any());
    }
```

- [x] **Step 2: 跑测试确认编译失败（红）**

```bash
cd /home/wang/playlab/hify/server && mvn test -Dtest='DocumentServiceTest,DatasetServiceTest'
```

Expected: `COMPILATION ERROR`（KnowledgeError/DocumentService/DTO 不存在、DatasetService 构造不匹配）。

- [x] **Step 3: 写 KnowledgeError**

`server/src/main/java/com/hify/knowledge/constant/KnowledgeError.java`：

```java
package com.hify.knowledge.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * knowledge 模块错误码（15xxx 段，api-standards §5.2）。只放模块特有语义；
 * 资源不存在/权限等通用失败一律用 CommonError。发布后只增不改。
 */
public enum KnowledgeError implements ErrorCode {

    /** 文档内容为空或无法按文本解析。 */
    DOCUMENT_CONTENT_EMPTY(15001, HttpStatus.BAD_REQUEST, "文档内容为空或无法解析"),
    /** 文档格式不支持。15004 的号是 api-standards §5.3 预定义示例，必须用它。 */
    DOCUMENT_FORMAT_UNSUPPORTED(15004, HttpStatus.BAD_REQUEST, "文档格式不支持，当前仅支持 txt/md");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    KnowledgeError(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
```

- [x] **Step 4: 写 DTO**

`server/src/main/java/com/hify/knowledge/dto/DocumentResponse.java`：

```java
package com.hify.knowledge.dto;

import java.time.OffsetDateTime;

/** 文档视图。不含原始文件内容（bytea 大列不进列表/详情响应）。Long 序列化为 string；Integer 保持数字。 */
public record DocumentResponse(
        Long id,
        Long datasetId,
        String name,
        String fileType,
        Long fileSize,
        String status,
        Integer chunkCount,
        OffsetDateTime createTime,
        OffsetDateTime updateTime) {
}
```

`server/src/main/java/com/hify/knowledge/dto/ChunkResponse.java`：

```java
package com.hify.knowledge.dto;

/** 分段预览视图。position 从 1 起。 */
public record ChunkResponse(Long id, Integer position, String content) {
}
```

- [x] **Step 5: 写 DocumentService + 改 DatasetService**

`server/src/main/java/com/hify/knowledge/service/DocumentService.java`：

```java
package com.hify.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.constant.KnowledgeError;
import com.hify.knowledge.dto.ChunkResponse;
import com.hify.knowledge.dto.DocumentResponse;
import com.hify.knowledge.entity.Dataset;
import com.hify.knowledge.entity.KbChunk;
import com.hify.knowledge.entity.KbDocument;
import com.hify.knowledge.mapper.DatasetMapper;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.knowledge.mapper.KbDocumentMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文档业务逻辑：上传（同步提取+分段+落库）、列表、删除（级联软删分段）、分段预览。
 * 权限随所属 dataset 判（owner/Admin，复用 DatasetService.assertCanModify）。
 * upload 的提取与分段是纯内存操作（本地字节，无外部 IO），放在事务内不违反「事务内禁外部 IO」。
 */
@Service
public class DocumentService {

    private static final int NAME_MAX = 200;
    private static final int BATCH_SIZE = 1000;

    private final DatasetMapper datasetMapper;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final int chunkSize;
    private final int chunkOverlap;

    public DocumentService(DatasetMapper datasetMapper,
                           KbDocumentMapper documentMapper,
                           KbChunkMapper chunkMapper,
                           @Value("${hify.knowledge.chunk-size}") int chunkSize,
                           @Value("${hify.knowledge.chunk-overlap}") int chunkOverlap) {
        this.datasetMapper = datasetMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    @Transactional
    public DocumentResponse upload(Long datasetId, MultipartFile file, CurrentUser current) {
        Dataset dataset = loadDatasetOrThrow(datasetId);
        DatasetService.assertCanModify(dataset, current);

        String name = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        if (name.length() > NAME_MAX) {
            throw new BizException(CommonError.PARAM_INVALID, "文件名不能超过 " + NAME_MAX + " 个字符");
        }
        String fileType = fileTypeOf(name);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(KnowledgeError.DOCUMENT_CONTENT_EMPTY, "文档读取失败", e);
        }
        String text = new String(bytes, StandardCharsets.UTF_8); // md 按原文处理，不渲染
        List<String> pieces = TextChunker.split(text, chunkSize, chunkOverlap);
        if (pieces.isEmpty()) {
            throw new BizException(KnowledgeError.DOCUMENT_CONTENT_EMPTY);
        }

        KbDocument doc = new KbDocument();
        doc.setDatasetId(datasetId);
        doc.setName(name);
        doc.setFileType(fileType);
        doc.setFileSize((long) bytes.length);
        doc.setContent(bytes);
        doc.setStatus("ready"); // K2 同步流程一步到 ready；K3 异步化后改写状态机
        doc.setChunkCount(pieces.size());
        doc.setChunkSize(chunkSize);
        doc.setChunkOverlap(chunkOverlap);
        documentMapper.insert(doc);

        List<KbChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            KbChunk chunk = new KbChunk();
            chunk.setDocumentId(doc.getId());
            chunk.setDatasetId(datasetId);
            chunk.setPosition(i + 1);
            chunk.setContent(pieces.get(i));
            chunks.add(chunk);
        }
        Db.saveBatch(chunks, BATCH_SIZE); // database-standards §2.1：每批 ≤1000
        return toResponse(doc);
    }

    public PageResult<DocumentResponse> pageDocuments(Long datasetId, int page, int size) {
        assertPageParams(page, size);
        loadDatasetOrThrow(datasetId);
        Page<KbDocument> result = documentMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<KbDocument>()
                        // 大列不进列表（database-standards）：显式排除 content
                        .select(KbDocument.class, info -> !"content".equals(info.getColumn()))
                        .eq(KbDocument::getDatasetId, datasetId)
                        .orderByDesc(KbDocument::getId));
        return PageResult.of(result.getRecords().stream().map(this::toResponse).toList(),
                result.getTotal(), page, size);
    }

    @Transactional
    public void deleteDocument(Long id, CurrentUser current) {
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            return; // 幂等
        }
        Dataset dataset = datasetMapper.selectById(doc.getDatasetId());
        if (dataset == null) {
            return; // 所属库已删（级联应已删本文档），按幂等处理
        }
        DatasetService.assertCanModify(dataset, current);
        documentMapper.deleteById(id);
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, id));
    }

    public PageResult<ChunkResponse> pageChunks(Long documentId, int page, int size) {
        assertPageParams(page, size);
        if (documentMapper.selectById(documentId) == null) {
            throw new BizException(CommonError.NOT_FOUND, "文档不存在");
        }
        Page<KbChunk> result = chunkMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getDocumentId, documentId)
                        .orderByAsc(KbChunk::getPosition));
        return PageResult.of(result.getRecords().stream()
                        .map(c -> new ChunkResponse(c.getId(), c.getPosition(), c.getContent())).toList(),
                result.getTotal(), page, size);
    }

    private Dataset loadDatasetOrThrow(Long datasetId) {
        Dataset dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new BizException(CommonError.NOT_FOUND, "知识库不存在");
        }
        return dataset;
    }

    /** 扩展名判定（大小写不敏感）：仅 txt/md；其余 15004。不猜 MIME。 */
    private String fileTypeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) {
            return "txt";
        }
        if (lower.endsWith(".md")) {
            return "md";
        }
        throw new BizException(KnowledgeError.DOCUMENT_FORMAT_UNSUPPORTED);
    }

    private void assertPageParams(int page, int size) {
        if (page < 1 || size < 1 || size > 100 || (long) page * size > 10_000) {
            throw new BizException(CommonError.PARAM_INVALID, "分页参数非法或过深，请用筛选条件缩小范围");
        }
    }

    private DocumentResponse toResponse(KbDocument d) {
        return new DocumentResponse(d.getId(), d.getDatasetId(), d.getName(), d.getFileType(),
                d.getFileSize(), d.getStatus(), d.getChunkCount(), d.getCreateTime(), d.getUpdateTime());
    }
}
```

修改 `server/src/main/java/com/hify/knowledge/service/DatasetService.java` 三处：

5a. 构造函数与字段——新增两个 mapper（import `com.hify.knowledge.entity.KbChunk`、`com.hify.knowledge.entity.KbDocument`、`com.hify.knowledge.mapper.KbChunkMapper`、`com.hify.knowledge.mapper.KbDocumentMapper`）：

```java
    private final DatasetMapper datasetMapper;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;

    public DatasetService(DatasetMapper datasetMapper,
                          KbDocumentMapper documentMapper,
                          KbChunkMapper chunkMapper) {
        this.datasetMapper = datasetMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }
```

5b. `delete` 方法体升级为级联软删（K1 决策#6 兑现，方法签名/幂等不变）：

```java
    @Transactional
    public void delete(Long id, CurrentUser current) {
        Dataset dataset = datasetMapper.selectById(id);
        if (dataset == null) {
            return; // 幂等：删不存在的也算成功（api-standards 2.2）
        }
        assertCanModify(dataset, current);
        datasetMapper.deleteById(id);
        // 级联软删文档与分段（照 ⑦ 会话级联软删消息先例；@TableLogic 使 delete = update set deleted=true）
        documentMapper.delete(new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getDatasetId, id));
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDatasetId, id));
    }
```

5c. `assertCanModify` 从 `private` 改为**包级 static**（DocumentService 同包复用，避免逐字重复权限逻辑；行为不变）：

```java
    /** 团队共享制：仅 owner 或 Admin 可改/删（api-standards 第 6 节），否则 FORBIDDEN。包级 static 供同包 DocumentService 复用。 */
    static void assertCanModify(Dataset dataset, CurrentUser current) {
        if (!current.isAdmin() && !current.userId().equals(dataset.getOwnerId())) {
            throw new BizException(CommonError.FORBIDDEN, "仅创建者或管理员可操作该知识库");
        }
    }
```

- [x] **Step 6: 跑测试确认全绿**

```bash
cd /home/wang/playlab/hify/server && mvn test -Dtest='DocumentServiceTest,DatasetServiceTest,TextChunkerTest'
```

Expected: DocumentServiceTest 16 + DatasetServiceTest 16（15 + 新增级联 1）+ TextChunkerTest 8，全部 0 failures。

- [x] **Step 7: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/java/com/hify/knowledge server/src/test/java/com/hify/knowledge && git commit -m "feat(knowledge): DocumentService 上传/分段/预览 + 删除级联软删（TDD）"
```

---

### Task 4: DocumentController + 全局异常处理器 multipart 分支（TDD）+ 后端全量回归

**Files:**
- Create: `server/src/main/java/com/hify/knowledge/controller/DocumentController.java`
- Modify: `server/src/main/java/com/hify/infra/web/GlobalExceptionHandler.java`（新增两个 @ExceptionHandler）
- Test: `server/src/test/java/com/hify/knowledge/controller/DocumentControllerTest.java`

**Interfaces:**
- Consumes: Task 3 的 `DocumentService` 四方法；`CurrentUserHolder.current()`；`Result.ok`。
- Produces: HTTP 端点 `POST /api/v1/knowledge/datasets/{datasetId}/documents`（multipart 字段名 `file`）、`GET .../datasets/{datasetId}/documents`、`DELETE /api/v1/knowledge/documents/{id}`、`GET .../documents/{id}/chunks`——前端 Task 5 对接。

- [x] **Step 1: 写失败测试**

`server/src/test/java/com/hify/knowledge/controller/DocumentControllerTest.java`（安全栈 @Import 照 DatasetControllerTest；@WebMvcTest 自动装配 @RestControllerAdvice，缺 file 分支能测到）：

```java
package com.hify.knowledge.controller;

import com.hify.common.page.PageResult;
import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.knowledge.dto.ChunkResponse;
import com.hify.knowledge.dto.DocumentResponse;
import com.hify.knowledge.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private DocumentService documentService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private DocumentResponse sampleDoc() {
        return new DocumentResponse(20L, 10L, "faq.txt", "txt", 1024L, "ready", 3,
                OffsetDateTime.parse("2026-07-02T10:00:00+08:00"),
                OffsetDateTime.parse("2026-07-02T10:00:00+08:00"));
    }

    @Test
    void 上传_multipart成功_返回文档资源() throws Exception {
        when(documentService.upload(eq(10L), any(), any())).thenReturn(sampleDoc());
        mockMvc.perform(multipart("/api/v1/knowledge/datasets/10/documents")
                        .file(new MockMultipartFile("file", "faq.txt", "text/plain", "hello".getBytes()))
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("20"))          // Long→string
                .andExpect(jsonPath("$.data.fileSize").value("1024"))  // Long→string
                .andExpect(jsonPath("$.data.chunkCount").value(3));    // Integer 保持数字
    }

    @Test
    void 上传_缺file字段_400且10001() throws Exception {
        mockMvc.perform(multipart("/api/v1/knowledge/datasets/10/documents")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 上传_未登录_401() throws Exception {
        mockMvc.perform(multipart("/api/v1/knowledge/datasets/10/documents")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 文档列表_返回PageResult() throws Exception {
        when(documentService.pageDocuments(eq(10L), eq(1), eq(20)))
                .thenReturn(PageResult.of(List.of(sampleDoc()), 1, 1, 20));
        mockMvc.perform(get("/api/v1/knowledge/datasets/10/documents?page=1&size=20")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].name").value("faq.txt"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    @Test
    void 删除文档_成功_data为null() throws Exception {
        mockMvc.perform(delete("/api/v1/knowledge/documents/20")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(documentService).deleteDocument(eq(20L), any());
    }

    @Test
    void 分段列表_返回position与content() throws Exception {
        when(documentService.pageChunks(eq(20L), eq(1), eq(20)))
                .thenReturn(PageResult.of(List.of(new ChunkResponse(30L, 1, "第一段")), 1, 1, 20));
        mockMvc.perform(get("/api/v1/knowledge/documents/20/chunks?page=1&size=20")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].position").value(1))
                .andExpect(jsonPath("$.data.list[0].content").value("第一段"));
    }
}
```

- [x] **Step 2: 跑测试确认编译失败（红）**

```bash
cd /home/wang/playlab/hify/server && mvn test -Dtest=DocumentControllerTest
```

Expected: `COMPILATION ERROR`（DocumentController 不存在）。

- [x] **Step 3: 写 Controller + 补异常处理器**

`server/src/main/java/com/hify/knowledge/controller/DocumentController.java`：

```java
package com.hify.knowledge.controller;

import com.hify.common.Result;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUserHolder;
import com.hify.knowledge.dto.ChunkResponse;
import com.hify.knowledge.dto.DocumentResponse;
import com.hify.knowledge.service.DocumentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档接口（成员族 /api/v1/knowledge/**）。documents 有自己的 id，删除/分段用顶级路由
 * /documents/{id}（api-standards §2.1「子资源有了自己的 id 就升为顶级」）。
 * 协议层：取当前用户 → 调 service → 包 Result；无业务逻辑、无 try-catch、无 @Transactional。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/datasets/{datasetId}/documents")
    public Result<DocumentResponse> upload(@PathVariable Long datasetId,
                                           @RequestPart("file") MultipartFile file) {
        return Result.ok(documentService.upload(datasetId, file, CurrentUserHolder.current()));
    }

    @GetMapping("/datasets/{datasetId}/documents")
    public Result<PageResult<DocumentResponse>> listDocuments(
            @PathVariable Long datasetId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(documentService.pageDocuments(datasetId, page, size));
    }

    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(id, CurrentUserHolder.current());
        return Result.ok(null);
    }

    @GetMapping("/documents/{id}/chunks")
    public Result<PageResult<ChunkResponse>> listChunks(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(documentService.pageChunks(id, page, size));
    }
}
```

在 `server/src/main/java/com/hify/infra/web/GlobalExceptionHandler.java` 的 `handleNoResource` 与 `handleUnexpected` 之间插入两个 handler（import `org.springframework.web.multipart.MaxUploadSizeExceededException`、`org.springframework.web.multipart.support.MissingServletRequestPartException`）：

```java
    /**
     * 上传超过 spring.servlet.multipart 限制（50MB）。归 10001/400（api-standards §6：超限返回 10001），
     * 否则会被兜底误判为 500。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Object>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return ResponseEntity
                .status(CommonError.PARAM_INVALID.status())
                .body(Result.fail(CommonError.PARAM_INVALID, "文件大小超过限制（单文件最大 50MB）"));
    }

    /** multipart 请求缺少必需的文件字段（如上传接口缺 'file'）。归 10001/400。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Object>> handleMissingPart(MissingServletRequestPartException ex) {
        return ResponseEntity
                .status(CommonError.PARAM_INVALID.status())
                .body(Result.fail(CommonError.PARAM_INVALID, "缺少文件参数 '" + ex.getRequestPartName() + "'"));
    }
```

- [x] **Step 4: 跑测试确认全绿**

```bash
cd /home/wang/playlab/hify/server && mvn test -Dtest=DocumentControllerTest
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`。

- [x] **Step 5: 后端全量回归（含 ModularityTests/ArchUnit）**

```bash
cd /home/wang/playlab/hify/server && mvn test
```

Expected: `BUILD SUCCESS`，0 failures（基线 309 + 本轮新增 TextChunker 8 + DocumentService 16 + Dataset 级联 1 + Controller 6 = 340）。若 Modulith 红：检查 knowledge 是否误 import 其他模块；GlobalExceptionHandler 属 infra，knowledge 不 import 它（异常经 Spring 分发，无编译依赖）。

- [x] **Step 6: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/java/com/hify/knowledge/controller server/src/test/java/com/hify/knowledge/controller server/src/main/java/com/hify/infra/web/GlobalExceptionHandler.java && git commit -m "feat(knowledge): 文档上传/列表/删除/分段预览接口 + multipart 异常转 10001（TDD）"
```

---

### Task 5: 前端类型 + API 层扩展（TDD）

**Files:**
- Modify: `web/src/types/knowledge.ts`（追加类型）
- Modify: `web/src/api/knowledge.ts`（追加四函数）
- Modify: `web/src/api/__tests__/knowledge.spec.ts`（追加用例）

**Interfaces:**
- Consumes: 既有 `request`、`PageResult`（'@/types/app'）。
- Produces（Task 6 页面依赖）: 类型 `KbDocument`（**不能命名为 `Document`**，与 DOM 内置类型撞名）、`Chunk`、`DocumentStatus`；函数 `uploadDocument(datasetId: string, file: File)` / `listDocuments(datasetId: string, params: {page: number; size: number})` / `deleteDocument(id: string)` / `listChunks(documentId: string, params: {page: number; size: number})`。

- [x] **Step 1: 追加失败测试**

在 `web/src/api/__tests__/knowledge.spec.ts` 的 describe 内追加（并把顶部 import 补上四个新函数）：

```typescript
  it('uploadDocument → POST /knowledge/datasets/{id}/documents + FormData(file)', () => {
    const file = new File(['hello'], 'a.txt', { type: 'text/plain' })
    uploadDocument('10', file)
    expect(request.post).toHaveBeenCalledWith(
      '/knowledge/datasets/10/documents',
      expect.any(FormData),
    )
    const fd = vi.mocked(request.post).mock.calls[0][1] as FormData
    expect(fd.get('file')).toBe(file)
  })
  it('listDocuments → GET /knowledge/datasets/{id}/documents + 分页 params', () => {
    listDocuments('10', { page: 1, size: 20 })
    expect(request.get).toHaveBeenCalledWith('/knowledge/datasets/10/documents', {
      params: { page: 1, size: 20 },
    })
  })
  it('deleteDocument → DELETE /knowledge/documents/{id}', () => {
    deleteDocument('20')
    expect(request.delete).toHaveBeenCalledWith('/knowledge/documents/20')
  })
  it('listChunks → GET /knowledge/documents/{id}/chunks + 分页 params', () => {
    listChunks('20', { page: 1, size: 10 })
    expect(request.get).toHaveBeenCalledWith('/knowledge/documents/20/chunks', {
      params: { page: 1, size: 10 },
    })
  })
```

- [x] **Step 2: 跑测试确认失败（红）**

```bash
cd /home/wang/playlab/hify/web && pnpm vitest run src/api/__tests__/knowledge.spec.ts
```

Expected: FAIL（新函数未导出）。

- [x] **Step 3: 追加类型与 API 函数**

`web/src/types/knowledge.ts` 文末追加：

```typescript
/** 文档处理状态（对齐后端 kb_document.status 四态）。K2 同步流程一步到 ready。 */
export type DocumentStatus = 'pending' | 'processing' | 'ready' | 'failed'

/** 文档视图（对齐后端 DocumentResponse）。命名 KbDocument 避免与 DOM 内置 Document 撞名。
 *  id/datasetId/fileSize 为 string（Long 序列化）；chunkCount 是 Integer → number。 */
export interface KbDocument {
  id: string
  datasetId: string
  name: string
  fileType: 'txt' | 'md'
  fileSize: string
  status: DocumentStatus
  chunkCount: number
  createTime: string
  updateTime: string
}

/** 分段预览（对齐后端 ChunkResponse）。position 从 1 起。 */
export interface Chunk {
  id: string
  position: number
  content: string
}
```

`web/src/api/knowledge.ts` 文末追加（顶部 import 补 `KbDocument, Chunk`）：

```typescript
const DOC_BASE = '/knowledge/documents'

/** 上传文档（multipart，字段名 file）。后端：POST /api/v1/knowledge/datasets/{id}/documents */
export function uploadDocument(datasetId: string, file: File) {
  const fd = new FormData()
  fd.append('file', file)
  return request.post<KbDocument>(`${BASE}/${datasetId}/documents`, fd)
}

/** 文档分页列表。后端：GET .../datasets/{id}/documents */
export function listDocuments(datasetId: string, params: { page: number; size: number }) {
  return request.get<PageResult<KbDocument>>(`${BASE}/${datasetId}/documents`, { params })
}

/** 删除文档（级联软删分段）。后端：DELETE /api/v1/knowledge/documents/{id} */
export function deleteDocument(id: string) {
  return request.delete<void>(`${DOC_BASE}/${id}`)
}

/** 分段分页列表（预览）。后端：GET .../documents/{id}/chunks */
export function listChunks(documentId: string, params: { page: number; size: number }) {
  return request.get<PageResult<Chunk>>(`${DOC_BASE}/${documentId}/chunks`, { params })
}
```

- [x] **Step 4: 跑测试确认全绿**

```bash
cd /home/wang/playlab/hify/web && pnpm vitest run src/api/__tests__/knowledge.spec.ts
```

Expected: `9 passed`（K1 的 5 + 新增 4）。

- [x] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src/types/knowledge.ts web/src/api/knowledge.ts web/src/api/__tests__/knowledge.spec.ts && git commit -m "feat(web): 文档上传/列表/删除/分段 API 层 + 类型（TDD）"
```

---

### Task 6: 路由 + KnowledgeList 入口 + DatasetDetail 页面（TDD）+ 前端全量回归

**Files:**
- Modify: `web/src/router/index.ts`（新增 `/knowledge/:id`）
- Modify: `web/src/views/knowledge/KnowledgeList.vue`（名称列变链接）
- Modify: `web/src/views/knowledge/__tests__/KnowledgeList.spec.ts`（补 vue-router mock + 跳转用例）
- Create: `web/src/views/knowledge/DatasetDetail.vue`
- Test: `web/src/views/knowledge/__tests__/DatasetDetail.spec.ts`

**Interfaces:**
- Consumes: Task 5 的 API 函数与类型；既有 `getDataset`、`useUserStore`、`PageHeader`/`ContentCard`、`formatDateTime`。
- Produces: 路由 `/knowledge/:id`（name `DatasetDetail`）；用户可见的文档管理页。

- [x] **Step 1: 写失败测试**

`web/src/views/knowledge/__tests__/DatasetDetail.spec.ts`：

```typescript
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import {
  getDataset, listDocuments, uploadDocument, deleteDocument, listChunks,
} from '@/api/knowledge'
import type { Dataset, KbDocument, Chunk } from '@/types/knowledge'
import type { PageResult } from '@/types/app'
import { useUserStore } from '@/stores/user'
import DatasetDetail from '@/views/knowledge/DatasetDetail.vue'

vi.mock('@/api/knowledge', () => ({
  getDataset: vi.fn(), listDatasets: vi.fn(), createDataset: vi.fn(),
  updateDataset: vi.fn(), deleteDataset: vi.fn(),
  uploadDocument: vi.fn(), listDocuments: vi.fn(), deleteDocument: vi.fn(), listChunks: vi.fn(),
}))

const routerPush = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '10' } }),
  useRouter: () => ({ push: routerPush }),
}))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

function page<T>(list: T[]): PageResult<T> {
  return { list, total: String(list.length), page: '1', size: '20' }
}
const DATASET: Dataset = {
  id: '10', name: '客服知识库', description: '售后答疑', ownerId: '7',
  createTime: '2026-07-02T10:00:00+08:00', updateTime: '2026-07-02T10:00:00+08:00',
}
const DOC: KbDocument = {
  id: '20', datasetId: '10', name: 'faq.txt', fileType: 'txt', fileSize: '1024',
  status: 'ready', chunkCount: 3,
  createTime: '2026-07-02T10:00:00+08:00', updateTime: '2026-07-02T10:00:00+08:00',
}
const CHUNK: Chunk = { id: '30', position: 1, content: '第一段内容' }

async function mountPage() {
  const wrapper = mount(DatasetDetail, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  return wrapper
}

async function selectFile(wrapper: ReturnType<typeof mount>, name: string) {
  const input = wrapper.find('input[type="file"]')
  const file = new File(['hello world'], name, { type: 'text/plain' })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

describe('DatasetDetail', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(getDataset).mockResolvedValue(DATASET)
    vi.mocked(listDocuments).mockResolvedValue(page([DOC]))
    const store = useUserStore()
    store.user = { id: '7', username: 'bob', role: 'member' } // 当前用户 = owner
  })

  it('挂载：拉取库信息与文档列表并渲染', async () => {
    const wrapper = await mountPage()
    expect(getDataset).toHaveBeenCalledWith('10')
    expect(listDocuments).toHaveBeenCalledWith('10', { page: 1, size: 20 })
    expect(wrapper.text()).toContain('客服知识库')
    expect(wrapper.text()).toContain('faq.txt')
  })

  it('owner 可见上传控件与删除按钮', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('input[type="file"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="doc-delete-20"]').exists()).toBe(true)
  })

  it('非 owner 非 admin：无上传控件、无删除按钮，但能看列表', async () => {
    const store = useUserStore()
    store.user = { id: '999', username: 'carol', role: 'member' }
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('faq.txt')
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="doc-delete-20"]').exists()).toBe(false)
  })

  it('选择 txt 文件触发上传并刷新列表', async () => {
    vi.mocked(uploadDocument).mockResolvedValue(DOC)
    const wrapper = await mountPage()
    await selectFile(wrapper, 'new.txt')
    expect(uploadDocument).toHaveBeenCalledWith('10', expect.any(File))
    expect(listDocuments).toHaveBeenCalledTimes(2) // 挂载 1 次 + 上传成功后刷新 1 次
  })

  it('选择不支持的扩展名：前端拦截不调上传', async () => {
    const wrapper = await mountPage()
    await selectFile(wrapper, 'report.pdf')
    expect(uploadDocument).not.toHaveBeenCalled()
  })

  it('删除文档：确认后调用并刷新', async () => {
    vi.mocked(deleteDocument).mockResolvedValue(undefined)
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = await mountPage()
    await wrapper.find('[data-test="doc-delete-20"]').trigger('click')
    await flushPromises()
    expect(deleteDocument).toHaveBeenCalledWith('20')
  })

  it('查看分段：打开抽屉并分页拉取', async () => {
    vi.mocked(listChunks).mockResolvedValue(page([CHUNK]))
    const wrapper = await mountPage()
    await wrapper.find('[data-test="doc-chunks-20"]').trigger('click')
    await flushPromises()
    expect(listChunks).toHaveBeenCalledWith('20', { page: 1, size: 10 })
    expect(document.body.textContent).toContain('第一段内容') // el-drawer 传送到 body
  })

  it('文档空列表：渲染空态不报错', async () => {
    vi.mocked(listDocuments).mockResolvedValue(page([]))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="doc-table"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('faq.txt')
  })
})
```

在 `web/src/views/knowledge/__tests__/KnowledgeList.spec.ts` 顶部追加 router mock（与 AppList.spec 同款），并在 describe 内追加跳转用例：

```typescript
// 顶部（vi.mock('@/api/knowledge') 之后）追加：
const routerPush = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }) }))

// describe 内追加：
  it('点名称链接跳转详情页', async () => {
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="open-1"]').trigger('click')
    expect(routerPush).toHaveBeenCalledWith('/knowledge/1')
  })
```

- [x] **Step 2: 跑测试确认失败（红）**

```bash
cd /home/wang/playlab/hify/web && pnpm vitest run src/views/knowledge
```

Expected: DatasetDetail.spec 全部 FAIL（组件不存在）；KnowledgeList 新用例 FAIL（无 open-1 链接）。

- [x] **Step 3: 实现——路由、列表入口、详情页**

3a. `web/src/router/index.ts` 在 KnowledgeList 路由对象之后插入：

```typescript
  {
    path: '/knowledge/:id',
    name: 'DatasetDetail',
    component: () => import('@/views/knowledge/DatasetDetail.vue'),
    meta: { requiresAuth: true, title: '知识库详情' },
  },
```

3b. `web/src/views/knowledge/KnowledgeList.vue`：script 顶部 import 增加 `import { useRouter } from 'vue-router'`，声明 `const router = useRouter()` 与：

```typescript
function openDetail(row: Dataset) {
  router.push(`/knowledge/${row.id}`)
}
```

模板中名称列由 `<el-table-column prop="name" label="名称" />` 改为：

```vue
        <el-table-column label="名称">
          <template #default="{ row }">
            <el-link
              type="primary"
              :underline="false"
              :data-test="`open-${(row as Dataset).id}`"
              @click="openDetail(row as Dataset)"
              >{{ (row as Dataset).name }}</el-link
            >
          </template>
        </el-table-column>
```

3c. 新建 `web/src/views/knowledge/DatasetDetail.vue`：

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
import {
  getDataset, listDocuments, uploadDocument, deleteDocument, listChunks,
} from '@/api/knowledge'
import type { Dataset, KbDocument, Chunk, DocumentStatus } from '@/types/knowledge'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const MAX_SIZE = 50 * 1024 * 1024
const CHUNK_PAGE_SIZE = 10

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const datasetId = String(route.params.id)

const dataset = ref<Dataset | null>(null)
const docs = ref<KbDocument[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const uploading = ref(false)

/** 团队共享制：仅 owner 或 Admin 可上传/删除（与后端 10004 双保险）。 */
const canModify = computed(
  () => !!dataset.value && (userStore.isAdmin || dataset.value.ownerId === userStore.user?.id),
)

async function loadDataset() {
  dataset.value = await getDataset(datasetId)
}
async function loadDocs() {
  loading.value = true
  try {
    const res = await listDocuments(datasetId, { page: page.value, size: size.value })
    docs.value = res.list
    total.value = Number(res.total)
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  loadDataset()
  loadDocs()
})

function onPageChange(p: number) {
  page.value = p
  loadDocs()
}

// —— 上传（el-upload 自定义 http-request；前端先拦截扩展名/大小，双保险）——
function beforeUpload(file: File): boolean {
  if (!/\.(txt|md)$/i.test(file.name)) {
    ElMessage.error('仅支持 txt / md 文件')
    return false
  }
  if (file.size > MAX_SIZE) {
    ElMessage.error('文件不能超过 50MB')
    return false
  }
  return true
}
async function doUpload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    await uploadDocument(datasetId, options.file as unknown as File)
    ElMessage.success('上传成功')
    await loadDocs()
  } catch {
    /* 失败（15004/15001 等）由 request 拦截器统一 toast */
  } finally {
    uploading.value = false
  }
}

async function onDelete(row: KbDocument) {
  try {
    await ElMessageBox.confirm(
      `确定删除文档「${row.name}」？其全部分段将一并删除。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteDocument(row.id)
    ElMessage.success('已删除')
    await loadDocs()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

// —— 分段预览抽屉 ——
const drawerVisible = ref(false)
const chunkDoc = ref<KbDocument | null>(null)
const chunks = ref<Chunk[]>([])
const chunkTotal = ref(0)
const chunkPage = ref(1)

async function openChunks(row: KbDocument) {
  chunkDoc.value = row
  chunkPage.value = 1
  drawerVisible.value = true
  await loadChunks()
}
async function loadChunks() {
  if (!chunkDoc.value) return
  const res = await listChunks(chunkDoc.value.id, { page: chunkPage.value, size: CHUNK_PAGE_SIZE })
  chunks.value = res.list
  chunkTotal.value = Number(res.total)
}
function onChunkPageChange(p: number) {
  chunkPage.value = p
  loadChunks()
}

const STATUS_LABEL: Record<DocumentStatus, string> = {
  pending: '待处理', processing: '处理中', ready: '就绪', failed: '失败',
}
const STATUS_TAG: Record<DocumentStatus, 'info' | 'warning' | 'success' | 'danger'> = {
  pending: 'info', processing: 'warning', ready: 'success', failed: 'danger',
}

function formatFileSize(bytes: string): string {
  const n = Number(bytes)
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <div class="dataset-detail">
    <PageHeader :title="dataset?.name ?? '知识库'" :description="dataset?.description ?? ''">
      <el-button data-test="back" @click="router.push('/knowledge')">返回列表</el-button>
      <el-upload
        v-if="canModify"
        accept=".txt,.md"
        :show-file-list="false"
        :before-upload="beforeUpload"
        :http-request="doUpload"
      >
        <el-button type="primary" data-test="upload-open" :loading="uploading">上传文档</el-button>
      </el-upload>
    </PageHeader>

    <ContentCard>
      <el-table v-loading="loading" :data="docs" data-test="doc-table">
        <el-table-column prop="name" label="名称" show-overflow-tooltip />
        <el-table-column label="格式" width="80">
          <template #default="{ row }">
            <el-tag>{{ (row as KbDocument).fileType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatFileSize((row as KbDocument).fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="分段数" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[(row as KbDocument).status]">
              {{ STATUS_LABEL[(row as KbDocument).status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传时间">
          <template #default="{ row }">{{ formatDateTime((row as KbDocument).createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button
              size="small"
              :data-test="`doc-chunks-${(row as KbDocument).id}`"
              @click="openChunks(row as KbDocument)"
              >查看分段</el-button
            >
            <el-button
              v-if="canModify"
              size="small"
              type="danger"
              :data-test="`doc-delete-${(row as KbDocument).id}`"
              @click="onDelete(row as KbDocument)"
              >删除</el-button
            >
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="dataset-detail__pager"
        layout="prev, pager, next, total"
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="onPageChange"
      />
    </ContentCard>

    <el-drawer v-model="drawerVisible" :title="`分段预览 — ${chunkDoc?.name ?? ''}`" size="45%">
      <div v-for="c in chunks" :key="c.id" class="dataset-detail__chunk">
        <div class="dataset-detail__chunk-pos">段 {{ c.position }}</div>
        <div class="dataset-detail__chunk-content">{{ c.content }}</div>
      </div>
      <el-pagination
        small
        layout="prev, pager, next, total"
        :total="chunkTotal"
        :current-page="chunkPage"
        :page-size="CHUNK_PAGE_SIZE"
        @current-change="onChunkPageChange"
      />
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.dataset-detail__pager {
  margin-top: $spacing-md;
  justify-content: flex-end;
}
.dataset-detail__chunk {
  margin-bottom: $spacing-md;
  padding: $spacing-sm;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}
.dataset-detail__chunk-pos {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-bottom: 4px;
}
.dataset-detail__chunk-content {
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
```

- [x] **Step 4: 跑测试确认全绿**

```bash
cd /home/wang/playlab/hify/web && pnpm vitest run src/views/knowledge
```

Expected: DatasetDetail 8 passed + KnowledgeList 10 passed（9 + 新增 1）。

- [x] **Step 5: 前端全量回归 + 类型检查**

```bash
cd /home/wang/playlab/hify/web && pnpm test && pnpm typecheck
```

Expected: 全部通过（既有 183 + api 4 + KnowledgeList 1 + DatasetDetail 8 = 196），`vue-tsc` 零错误。

- [x] **Step 6: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src/router/index.ts web/src/views/knowledge && git commit -m "feat(web): 知识库详情页（上传/文档列表/分段预览抽屉）+ 列表入口（TDD）"
```

---

### Task 7: 自检 + 手动验收

**Files:**
- Modify: `docs/self-check.md`（文末追加）

- [x] **Step 1: 追加自检**

在 `docs/self-check.md` 文末追加：

```markdown
## 2026-07-02 knowledge K2 文档上传与分段

- [x] V14 只新增两表；kb_chunk 无 embedding 列（K3 迁移补 vector(1024)）；FK 无 cascade（软删体系）
- [x] 路由核对 api-standards：嵌套一级 /datasets/{id}/documents；documents 升顶级 /documents/{id}/chunks
- [x] 错误码仅新增 15001/15004（15004 为 api-standards 预定义号）；multipart 超限/缺文件转 10001
- [x] 分段参数走 yml（500/50）并记录在 kb_document 行；Db.saveBatch ≤1000 + reWriteBatchedInserts
- [x] 文档列表排除 content 大列；删除级联软删（库→文档→分段）
- [x] 后端 mvn test 全绿（无 -q）；前端 pnpm test + typecheck 全绿
- [x] 手动验收：传 txt/md → 看分段数与状态 → 传 pdf 报 15004 → 空文件报 15001 → 分段预览翻页 → member 门控 → 删文档/删库级联
```

- [ ] **Step 2: 手动验收（用户执行）**（按用户指令跳过）

启动后端（V14 自动迁移）与前端 dev，按上面清单走。Postman 可在 `docs/verify/knowledge-k1.postman_collection.json` 基础上加上传请求（form-data，key=file 选文件）验证后端侧。

- [x] **Step 3: Commit**

```bash
cd /home/wang/playlab/hify && git add docs/self-check.md && git commit -m "docs: knowledge K2 自检"
```
