# K4 知识检索接入对话 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 应用可绑定知识库；聊天时按当前问题向量检索命中段拼进系统提示词（失败降级）；知识库详情页提供命中测试工具；随本轮引入 Testcontainers 连库测试基建。

**Architecture:** 手写注解 SQL 做 pgvector 余弦检索（spec 决策 6），knowledge 新建首个 Facade 暴露 `retrieve(datasetIds, query)`（topK/阈值在 knowledge 内部消化）；app 模块建 `app_dataset_rel` 关系表存绑定；conversation 在事务间隙检索并拼提示词，try-catch 降级。spec：`docs/superpowers/specs/2026-07-06-knowledge-k4-retrieval-chat-design.md`。

**Tech Stack:** Spring Boot 3 + MyBatis-Plus + pgvector（`<=>` 余弦距离）+ Testcontainers（`pgvector/pgvector:pg16` 单例容器）+ Vue 3 + Element Plus + vitest。

## Global Constraints

- TDD 先红后绿；每个 Task 结尾提交一次（commit message 风格照 git log 既有惯例）。
- **mvn 判定看退出码/失败摘要，禁止 `grep BUILD SUCCESS`**（`-q` 会静音；见项目既有教训）。后端命令统一 `cd /home/wang/playlab/hify/server && mvn test -Dtest=<类名>`；前端 `cd /home/wang/playlab/hify/web && pnpm test <文件路径>`。
- 连库测试（继承 `PgIntegrationTest` 的类）**需要本机 Docker 运行中**；首次运行会拉取 `pgvector/pgvector:pg16` 镜像。
- Long 一律序列化为 string（infra JacksonConfig 全局处理，List<Long> 元素同样生效，测试断言按 string 写）。
- 错误码零新增：复用 10001/10005/12003/12004/12006。
- DTO 不 import entity（ArchUnit 强制）；跨模块 DTO 放 `api` 顶层包（不进 `api/dto` 子包——Modulith 1.4.1 不暴露子包）。
- `@Transactional` 内零外部 IO；检索发生在 conversation 两事务间隙。
- 不改旧迁移，只新增 V18；不引运行时新依赖（Testcontainers 为 test scope，版本由 Spring Boot BOM 管理）。
- 前端既有 `App` fixture 都要补 `datasetIds: []`（TS 必填字段），vitest 不做类型检查，靠 `pnpm build`（vue-tsc）兜底。

## File Map（全轮改动一览）

| 模块 | 新建 | 修改 |
|---|---|---|
| 迁移 | `V18__create_app_dataset_rel.sql` | — |
| 测试基建 | `test/java/com/hify/support/PgIntegrationTest.java` | `server/pom.xml` |
| knowledge | `api/KnowledgeFacade.java`、`api/RetrievedChunk.java`、`service/RetrievalService.java`、`service/KnowledgeFacadeImpl.java`、`dto/ChunkHit.java`、`dto/RetrieveTestRequest.java` | `mapper/KbChunkMapper.java`、`controller/DatasetController.java`、`application.yml` |
| app | `entity/AppDatasetRel.java`、`mapper/AppDatasetRelMapper.java` | `dto/CreateAppRequest.java`、`dto/UpdateAppRequest.java`、`dto/AppResponse.java`、`api/AppRuntimeView.java`、`service/AppService.java`、`service/AppFacadeImpl.java` |
| conversation | — | `service/ConversationService.java` |
| web | — | `types/app.ts`、`types/knowledge.ts`、`api/knowledge.ts`、`views/app/AppList.vue`、`views/knowledge/DatasetDetail.vue` + 对应 `__tests__` |
| docs | — | `docs/architecture/database-standards.md`、`CLAUDE.md`（技术栈一句） |

---

### Task 1: Testcontainers 基建 + V18 迁移

**Files:**
- Modify: `server/pom.xml`（测试依赖区，reactor-test 之后）
- Create: `server/src/main/resources/db/migration/V18__create_app_dataset_rel.sql`
- Create: `server/src/test/java/com/hify/support/PgIntegrationTest.java`
- Test: `server/src/test/java/com/hify/app/AppDatasetRelSchemaTest.java`

**Interfaces:**
- Consumes: 无（首任务）
- Produces: 抽象基类 `com.hify.support.PgIntegrationTest`（`@SpringBootTest`+`@Transactional`，静态单例 PG 容器，供后续所有连库测试继承）；表 `app_dataset_rel(id, app_id, dataset_id, deleted, create_time, update_time)`。

- [x] **Step 1: 写失败测试**

`server/src/test/java/com/hify/app/AppDatasetRelSchemaTest.java`：

```java
package com.hify.app;

import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V18 表结构与部分唯一索引行为验证（顺带首次真库跑通 V1~V18 全量 Flyway 迁移链）。 */
class AppDatasetRelSchemaTest extends PgIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Long appId;

    @BeforeEach
    void seedApp() {
        appId = jdbc.queryForObject(
                "insert into app(name, type, owner_id) values ('K4测试应用', 'chat', 1) returning id",
                Long.class);
    }

    @Test
    void 同一应用重复绑定同一知识库_违反部分唯一索引() {
        jdbc.update("insert into app_dataset_rel(app_id, dataset_id) values (?, ?)", appId, 100L);
        assertThrows(DuplicateKeyException.class, () ->
                jdbc.update("insert into app_dataset_rel(app_id, dataset_id) values (?, ?)", appId, 100L));
    }

    @Test
    void 软删后可重新绑定_部分唯一索引只看未删行() {
        jdbc.update("insert into app_dataset_rel(app_id, dataset_id, deleted) values (?, ?, true)", appId, 100L);
        jdbc.update("insert into app_dataset_rel(app_id, dataset_id) values (?, ?)", appId, 100L);
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from app_dataset_rel where app_id = ? and deleted = false",
                Integer.class, appId));
    }
}
```

- [x] **Step 2: 运行确认失败（编译错：PgIntegrationTest 不存在）**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=AppDatasetRelSchemaTest`
Expected: FAIL（compilation error: `com.hify.support.PgIntegrationTest` 不存在）

- [x] **Step 3: 加 pom 测试依赖**

在 `server/pom.xml` 测试依赖区（`reactor-test` 那个 `<dependency>` 之后、`</dependencies>` 之前）插入：

```xml
        <!-- Testcontainers：连库集成测试基建（K4 起引入；镜像 pgvector/pgvector:pg16 与生产 compose 同款） -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
```

版本不写——Spring Boot BOM（spring-boot-starter-parent）管理 org.testcontainers。

- [x] **Step 4: 写迁移 V18**

`server/src/main/resources/db/migration/V18__create_app_dataset_rel.sql`：

```sql
-- V18：应用↔知识库 多对多关系表（data-model.md 预留席位兑现，K4 绑定用）。
-- dataset_id 跨模块弱引用不建 FK（data-model 第 3 条）；app_id 模块内 FK 可建。
-- 绑定更新=全量替换（软删旧行+插新行），部分唯一索引只约束未删行。

create table app_dataset_rel (
    id          bigint      generated always as identity primary key,
    app_id      bigint      not null references app(id),
    dataset_id  bigint      not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table app_dataset_rel is '应用↔知识库多对多（app 模块）；dataset_id 跨模块弱引用';
create unique index app_dataset_rel_uq on app_dataset_rel (app_id, dataset_id) where deleted = false;
create index app_dataset_rel_dataset_idx on app_dataset_rel (dataset_id);
```

- [x] **Step 5: 写测试基类**

`server/src/test/java/com/hify/support/PgIntegrationTest.java`：

```java
package com.hify.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 连库集成测试基类（K4 引入，全模块复用）：静态单例 pgvector 容器 + 完整 Spring 上下文
 * （Flyway 全量迁移只跑一遍，顺带真验所有迁移脚本）。@Transactional 让每个测试自动回滚，
 * 各测试类共享容器但数据互不污染。运行前提：本机 Docker 已启动。
 */
@SpringBootTest
@Transactional
public abstract class PgIntegrationTest {

    // 官方 postgres 镜像无 vector 扩展，必须用 pgvector 镜像（与生产 docker-compose 同款）
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start(); // 单例：JVM 内所有子类共享；结束由 Testcontainers Ryuk 兜底回收
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

- [x] **Step 6: 运行测试通过**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=AppDatasetRelSchemaTest`
Expected: PASS（首次运行拉镜像会慢；Flyway 日志应显示 V1..V18 全部应用）

- [x] **Step 7: 既有测试回归（确保基建不干扰单测）**

Run: `cd /home/wang/playlab/hify/server && mvn test`
Expected: 全绿（退出码 0）

- [x] **Step 8: Commit**

```bash
cd /home/wang/playlab/hify && git add server/pom.xml server/src/main/resources/db/migration/V18__create_app_dataset_rel.sql server/src/test/java/com/hify/support/ server/src/test/java/com/hify/app/AppDatasetRelSchemaTest.java
git commit -m "feat(app): V18 应用↔知识库关系表 + Testcontainers 连库测试基建（TDD）"
```

---

### Task 2: 检索 SQL + DocumentProcessStore 直测（K3 留账）

**Files:**
- Create: `server/src/main/java/com/hify/knowledge/dto/ChunkHit.java`
- Modify: `server/src/main/java/com/hify/knowledge/mapper/KbChunkMapper.java`
- Test: `server/src/test/java/com/hify/knowledge/mapper/KbChunkRetrievalTest.java`
- Test: `server/src/test/java/com/hify/knowledge/service/DocumentProcessStoreTest.java`

**Interfaces:**
- Consumes: Task 1 的 `PgIntegrationTest`。
- Produces: `KbChunkMapper.searchByVector(List<Long> datasetIds, String qvec, int topK) → List<ChunkHit>`；`ChunkHit` getters：`getId()/getDocumentId()/getDocumentName()/getContent()/getScore()`（score 为 Double 相似度，越大越相关）。

- [x] **Step 1: 写失败测试（检索 SQL 真库行为）**

`server/src/test/java/com/hify/knowledge/mapper/KbChunkRetrievalTest.java`：

```java
package com.hify.knowledge.mapper;

import com.hify.knowledge.dto.ChunkHit;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手写向量检索 SQL 的真库测试：排序/分数/跨库过滤/软删与未嵌入排除/topK 截断（mock 测不到，K4 引 Testcontainers 的动因）。 */
class KbChunkRetrievalTest extends PgIntegrationTest {

    @Autowired
    private KbChunkMapper chunkMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private Long datasetA;
    private Long datasetB;
    private Long docA;
    private Long docB;

    /** 1024 维向量字面量：前两维为 (x, y)，其余为 0。夹角可控，余弦相似度可手算。 */
    private static String vec(double x, double y) {
        StringBuilder sb = new StringBuilder("[").append(x).append(',').append(y);
        sb.append(",0".repeat(1022));
        return sb.append(']').toString();
    }

    @BeforeEach
    void seed() {
        datasetA = insertDataset("K4检索A库");
        datasetB = insertDataset("K4检索B库");
        docA = insertDocument(datasetA, "a.txt");
        docB = insertDocument(datasetB, "b.txt");
    }

    private Long insertDataset(String name) {
        return jdbc.queryForObject(
                "insert into dataset(name, owner_id) values (?, 1) returning id", Long.class, name);
    }

    private Long insertDocument(Long datasetId, String name) {
        return jdbc.queryForObject("""
                insert into kb_document(dataset_id, name, file_type, file_size, content, status, chunk_size, chunk_overlap)
                values (?, ?, 'txt', 1, ?, 'ready', 500, 50) returning id""",
                Long.class, datasetId, name, new byte[0]);
    }

    private Long insertChunk(Long docId, Long dsId, int pos, String content, String vecLiteral) {
        return jdbc.queryForObject("""
                insert into kb_chunk(document_id, dataset_id, position, content, embedding)
                values (?, ?, ?, ?, ?::vector) returning id""",
                Long.class, docId, dsId, pos, content, vecLiteral);
    }

    @Test
    void 按相似度降序返回_带文档名与分数() {
        insertChunk(docA, datasetA, 1, "完全相关", vec(1, 0));   // cos=1.0
        insertChunk(docA, datasetA, 2, "部分相关", vec(1, 1));   // cos≈0.7071
        insertChunk(docA, datasetA, 3, "毫不相关", vec(0, 1));   // cos=0
        List<ChunkHit> hits = chunkMapper.searchByVector(List.of(datasetA), vec(1, 0), 10);
        assertEquals(3, hits.size());
        assertEquals("完全相关", hits.get(0).getContent());
        assertEquals("a.txt", hits.get(0).getDocumentName());
        assertEquals(1.0, hits.get(0).getScore(), 1e-4);
        assertEquals(0.7071, hits.get(1).getScore(), 1e-3);
        assertEquals("毫不相关", hits.get(2).getContent());
    }

    @Test
    void topK截断() {
        insertChunk(docA, datasetA, 1, "一", vec(1, 0));
        insertChunk(docA, datasetA, 2, "二", vec(1, 1));
        insertChunk(docA, datasetA, 3, "三", vec(0, 1));
        assertEquals(2, chunkMapper.searchByVector(List.of(datasetA), vec(1, 0), 2).size());
    }

    @Test
    void 跨库过滤_只搜指定知识库() {
        insertChunk(docA, datasetA, 1, "A库内容", vec(1, 0));
        insertChunk(docB, datasetB, 1, "B库内容", vec(1, 0));
        List<ChunkHit> onlyA = chunkMapper.searchByVector(List.of(datasetA), vec(1, 0), 10);
        assertEquals(1, onlyA.size());
        assertEquals("A库内容", onlyA.get(0).getContent());
        assertEquals(2, chunkMapper.searchByVector(List.of(datasetA, datasetB), vec(1, 0), 10).size());
    }

    @Test
    void 排除软删与未嵌入的分段() {
        insertChunk(docA, datasetA, 1, "正常段", vec(1, 0));
        Long deletedId = insertChunk(docA, datasetA, 2, "已删段", vec(1, 0));
        jdbc.update("update kb_chunk set deleted = true where id = ?", deletedId);
        jdbc.update("insert into kb_chunk(document_id, dataset_id, position, content) values (?, ?, 3, '未嵌入段')",
                docA, datasetA);
        List<ChunkHit> hits = chunkMapper.searchByVector(List.of(datasetA), vec(1, 0), 10);
        assertEquals(1, hits.size());
        assertEquals("正常段", hits.get(0).getContent());
        assertTrue(hits.stream().noneMatch(h -> h.getContent().contains("已删") || h.getContent().contains("未嵌入")));
    }
}
```

- [x] **Step 2: 运行确认失败（ChunkHit / searchByVector 不存在，编译错）**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=KbChunkRetrievalTest`
Expected: FAIL（compilation error）

- [x] **Step 3: 写 ChunkHit 投影 + Mapper 方法**

`server/src/main/java/com/hify/knowledge/dto/ChunkHit.java`：

```java
package com.hify.knowledge.dto;

/**
 * 向量检索命中投影（KbChunkMapper.searchByVector 结果映射，模块内用）。
 * 不复用 KbChunk 实体：多出 documentName/score 两个跨表计算列。score = 1 - 余弦距离，越大越相关。
 * POJO 而非 record：MyBatis 结果映射走 setter（map-underscore-to-camel-case）。
 */
public class ChunkHit {

    private Long id;
    private Long documentId;
    private String documentName;
    private String content;
    private Double score;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
}
```

`KbChunkMapper` 追加方法（import 补 `com.hify.knowledge.dto.ChunkHit`）：

```java
    /**
     * 向量检索（database-standards §2.1 先过滤后排序模板；K4 拍板手写 SQL，不走 VectorStore）。
     * {@code <=>} 为余弦距离，score = 1 - 距离；多库过滤用 foreach in（与 any(?) 等价，MyBatis 免数组 TypeHandler）。
     * 阈值过滤在 RetrievalService（Java 层），SQL 只管 topK——阈值进 where 会干扰 HNSW 索引走法。
     */
    @Select("""
            <script>
            select c.id, c.document_id, d.name as document_name, c.content,
                   1 - (c.embedding <![CDATA[<=>]]> #{qvec}::vector) as score
            from kb_chunk c
            join kb_document d on d.id = c.document_id
            where c.dataset_id in
              <foreach collection="datasetIds" item="dsId" open="(" separator="," close=")">#{dsId}</foreach>
              and c.deleted = false and d.deleted = false
              and c.embedding is not null
            order by c.embedding <![CDATA[<=>]]> #{qvec}::vector
            limit #{topK}
            </script>
            """)
    List<ChunkHit> searchByVector(@Param("datasetIds") List<Long> datasetIds,
                                  @Param("qvec") String qvec,
                                  @Param("topK") int topK);
```

- [x] **Step 4: 运行检索测试通过**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=KbChunkRetrievalTest`
Expected: PASS（4 个测试全绿）

- [x] **Step 5: 写 DocumentProcessStore 直测（K3 留账兑现，直接绿——被测代码已存在）**

`server/src/test/java/com/hify/knowledge/service/DocumentProcessStoreTest.java`：

```java
package com.hify.knowledge.service;

import com.hify.knowledge.entity.KbChunk;
import com.hify.knowledge.entity.KbDocument;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** K3 留账兑现：saveChunks（Db.saveBatch 组装）与 vectorLiteral→writeEmbeddings 写真库直测。 */
class DocumentProcessStoreTest extends PgIntegrationTest {

    @Autowired
    private DocumentProcessStore store;
    @Autowired
    private KbChunkMapper chunkMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private KbDocument doc;

    @BeforeEach
    void seed() {
        Long datasetId = jdbc.queryForObject(
                "insert into dataset(name, owner_id) values ('K4存储直测库', 1) returning id", Long.class);
        Long docId = jdbc.queryForObject("""
                insert into kb_document(dataset_id, name, file_type, file_size, content, status, chunk_size, chunk_overlap)
                values (?, 'store.txt', 'txt', 1, ?, 'processing', 500, 50) returning id""",
                Long.class, datasetId, new byte[0]);
        doc = new KbDocument();
        doc.setId(docId);
        doc.setDatasetId(datasetId);
    }

    @Test
    void saveChunks_批量落库且position从1起_并更新chunkCount() {
        store.saveChunks(doc, List.of("段一", "段二", "段三"));
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from kb_chunk where document_id = ?", Integer.class, doc.getId()));
        assertEquals(1, jdbc.queryForObject(
                "select position from kb_chunk where document_id = ? and content = '段一'", Integer.class, doc.getId()));
        assertEquals(3, jdbc.queryForObject(
                "select chunk_count from kb_document where id = ?", Integer.class, doc.getId()));
    }

    @Test
    void writeEmbeddings_vectorLiteral写入真库_维度1024可回读() {
        store.saveChunks(doc, List.of("待嵌段"));
        List<KbChunk> pending = chunkMapper.selectUnembedded(doc.getId());
        assertEquals(1, pending.size());
        float[] v = new float[1024];
        v[0] = 0.5f;
        v[1023] = -0.25f;
        store.writeEmbeddings(pending, List.of(v));
        assertEquals(1024, jdbc.queryForObject(
                "select vector_dims(embedding) from kb_chunk where id = ?", Integer.class, pending.get(0).getId()));
        assertEquals(0, chunkMapper.selectUnembedded(doc.getId()).size());
    }
}
```

- [x] **Step 6: 运行通过 + knowledge 全量回归**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='DocumentProcessStoreTest,KbChunkRetrievalTest' && mvn test`
Expected: 两条命令都 PASS（退出码 0）

- [x] **Step 7: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/java/com/hify/knowledge/ server/src/test/java/com/hify/knowledge/
git commit -m "feat(knowledge): 手写 pgvector 余弦检索 SQL + K3 留账 DocumentProcessStore 真库直测（TDD）"
```

---

### Task 3: RetrievalService + KnowledgeFacade（检索能力成型）

**Files:**
- Create: `server/src/main/java/com/hify/knowledge/api/RetrievedChunk.java`
- Create: `server/src/main/java/com/hify/knowledge/api/KnowledgeFacade.java`
- Create: `server/src/main/java/com/hify/knowledge/service/RetrievalService.java`
- Create: `server/src/main/java/com/hify/knowledge/service/KnowledgeFacadeImpl.java`
- Modify: `server/src/main/resources/application.yml`（`hify.knowledge` 下追加两键）
- Test: `server/src/test/java/com/hify/knowledge/service/RetrievalServiceTest.java`
- Test: `server/src/test/java/com/hify/knowledge/service/KnowledgeFacadeImplTest.java`

**Interfaces:**
- Consumes: Task 2 的 `KbChunkMapper.searchByVector` 与 `ChunkHit`；既有 `ProviderFacade.getEmbeddingModel()`、`DocumentProcessStore.vectorLiteral(float[])`（同包 static）。
- Produces: `KnowledgeFacade.retrieve(List<Long> datasetIds, String query) → List<RetrievedChunk>`、`KnowledgeFacade.validateDatasetIds(List<Long>)`；`RetrievedChunk(Long chunkId, Long documentId, String documentName, String content, double score)`；模块内 `RetrievalService.retrieve(List<Long>, String, int topK, double scoreThreshold)`（Task 4 命中测试用）。

- [x] **Step 1: 写失败测试**

`server/src/test/java/com/hify/knowledge/service/RetrievalServiceTest.java`：

```java
package com.hify.knowledge.service;

import com.hify.common.exception.BizException;
import com.hify.knowledge.api.RetrievedChunk;
import com.hify.knowledge.dto.ChunkHit;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.provider.api.ProviderFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private KbChunkMapper chunkMapper;
    private ProviderFacade providerFacade;
    private EmbeddingModel embeddingModel;
    private RetrievalService service;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(KbChunkMapper.class);
        providerFacade = mock(ProviderFacade.class);
        embeddingModel = mock(EmbeddingModel.class);
        // 全局默认 topK=4、阈值=0.3（对应 yml 缺省）
        service = new RetrievalService(chunkMapper, providerFacade, 4, 0.3);
    }

    private static ChunkHit hit(long id, String content, double score) {
        ChunkHit h = new ChunkHit();
        h.setId(id);
        h.setDocumentId(2L);
        h.setDocumentName("a.txt");
        h.setContent(content);
        h.setScore(score);
        return h;
    }

    private void stubEmbedding() {
        when(providerFacade.getEmbeddingModel()).thenReturn(embeddingModel);
        when(embeddingModel.embed(eq("问题"))).thenReturn(new float[]{0.1f, 0.2f});
    }

    @Test
    void 空datasetIds_短路返回空_不调embedding() {
        assertEquals(List.of(), service.retrieve(List.of(), "问题"));
        assertEquals(List.of(), service.retrieve(null, "问题"));
        verify(providerFacade, never()).getEmbeddingModel();
    }

    @Test
    void 命中按阈值过滤_低于0点3丢弃_并映射为RetrievedChunk() {
        stubEmbedding();
        when(chunkMapper.searchByVector(eq(List.of(9L)), anyString(), eq(4)))
                .thenReturn(List.of(hit(1L, "高分段", 0.83), hit(2L, "低分段", 0.1)));
        List<RetrievedChunk> out = service.retrieve(List.of(9L), "问题");
        assertEquals(1, out.size());
        assertEquals(new RetrievedChunk(1L, 2L, "a.txt", "高分段", 0.83), out.get(0));
    }

    @Test
    void 查询向量以字面量传给Mapper() {
        stubEmbedding();
        when(chunkMapper.searchByVector(eq(List.of(9L)), eq("[0.1,0.2]"), eq(4))).thenReturn(List.of());
        assertTrue(service.retrieve(List.of(9L), "问题").isEmpty());
        verify(chunkMapper).searchByVector(eq(List.of(9L)), eq("[0.1,0.2]"), eq(4));
    }

    @Test
    void 显式topK与阈值覆盖默认值() {
        stubEmbedding();
        when(chunkMapper.searchByVector(eq(List.of(9L)), anyString(), eq(10)))
                .thenReturn(List.of(hit(1L, "低分也保留", 0.05)));
        List<RetrievedChunk> out = service.retrieve(List.of(9L), "问题", 10, 0.0);
        assertEquals(1, out.size());
    }

    @Test
    void embedding异常原样透传_降级由调用方决定() {
        when(providerFacade.getEmbeddingModel())
                .thenThrow(new BizException(com.hify.common.exception.CommonError.DEPENDENCY_UNAVAILABLE, "未配置"));
        assertThrows(BizException.class, () -> service.retrieve(List.of(9L), "问题"));
    }
}
```

`server/src/test/java/com/hify/knowledge/service/KnowledgeFacadeImplTest.java`：

```java
package com.hify.knowledge.service;

import com.hify.common.exception.BizException;
import com.hify.knowledge.api.RetrievedChunk;
import com.hify.knowledge.entity.Dataset;
import com.hify.knowledge.mapper.DatasetMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeFacadeImplTest {

    private RetrievalService retrievalService;
    private DatasetMapper datasetMapper;
    private KnowledgeFacadeImpl facade;

    @BeforeEach
    void setUp() {
        retrievalService = mock(RetrievalService.class);
        datasetMapper = mock(DatasetMapper.class);
        facade = new KnowledgeFacadeImpl(retrievalService, datasetMapper);
    }

    @Test
    void retrieve_委托RetrievalService默认参数入口() {
        List<RetrievedChunk> expected = List.of(new RetrievedChunk(1L, 2L, "a.txt", "内容", 0.9));
        when(retrievalService.retrieve(List.of(9L), "问题")).thenReturn(expected);
        assertEquals(expected, facade.retrieve(List.of(9L), "问题"));
    }

    @Test
    void validateDatasetIds_空或null直接通过_不查库() {
        assertDoesNotThrow(() -> facade.validateDatasetIds(null));
        assertDoesNotThrow(() -> facade.validateDatasetIds(List.of()));
        verifyNoInteractions(datasetMapper);
    }

    @Test
    void validateDatasetIds_全部存在_通过_重复id按去重计数() {
        when(datasetMapper.selectCount(ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<Dataset>>any()))
                .thenReturn(2L);
        assertDoesNotThrow(() -> facade.validateDatasetIds(List.of(9L, 8L, 9L)));
    }

    @Test
    void validateDatasetIds_缺失_抛10005() {
        when(datasetMapper.selectCount(ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<Dataset>>any()))
                .thenReturn(1L);
        BizException e = assertThrows(BizException.class, () -> facade.validateDatasetIds(List.of(9L, 404L)));
        assertEquals(10005, e.getError().code());
    }
}
```

注：`BizException` 取错误码的访问器名以 `com.hify.common.exception.BizException` 现有实现为准（K3 测试有先例，照抄既有写法；若是 `getCode()` 之类就相应调整断言行）。

- [x] **Step 2: 运行确认失败（编译错）**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='RetrievalServiceTest,KnowledgeFacadeImplTest'`
Expected: FAIL（compilation error：RetrievedChunk / RetrievalService / KnowledgeFacadeImpl 不存在）

- [x] **Step 3: 写实现**

`server/src/main/java/com/hify/knowledge/api/RetrievedChunk.java`：

```java
package com.hify.knowledge.api;

/**
 * 向量检索命中段（跨模块视图）：conversation 拼提示词、命中测试端点响应共用。
 * 放 api 顶层包（Modulith 1.4.1 不暴露 api/dto 子包）。score = 1 - 余弦距离 ∈ [~0,1]，越大越相关。
 */
public record RetrievedChunk(Long chunkId, Long documentId, String documentName, String content, double score) {
}
```

`server/src/main/java/com/hify/knowledge/api/KnowledgeFacade.java`：

```java
package com.hify.knowledge.api;

import java.util.List;

/**
 * knowledge 模块对外门面（一个模块最多一个 Facade，Modulith 强制）。
 * 刻意只暴露两参 retrieve：topK/相似度阈值是 knowledge 内部的全局配置
 * （hify.knowledge.retrieval.*），调用方不需要也不应该看见；带显式参数的入口是模块内部
 * RetrievalService，仅命中测试端点覆写用。
 */
public interface KnowledgeFacade {

    /**
     * 向量检索：返回按相似度降序、已过阈值的命中段（最多全局 topK 条）。空/null datasetIds 返回空列表。
     * 未配 embedding 模型（12006）/供应商故障（12003/12004）抛 BizException——降级与否由调用方决定
     * （conversation 降级继续答；命中测试原样抛给前端）。
     */
    List<RetrievedChunk> retrieve(List<Long> datasetIds, String query);

    /** 绑定校验：datasetIds 全部存在且未删则通过，否则抛 BizException(10005)。空/null 直接通过。app 创建/编辑时调。 */
    void validateDatasetIds(List<Long> datasetIds);
}
```

`server/src/main/java/com/hify/knowledge/service/RetrievalService.java`：

```java
package com.hify.knowledge.service;

import com.hify.knowledge.api.RetrievedChunk;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.provider.api.ProviderFacade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量检索：query → embedding（批量池韧性，K3 现成）→ 手写 SQL 取 topK → Java 层按阈值过滤。
 * 不带 @Transactional——embed 是真实外部 IO，检索 SQL 是只读单查。
 * 阈值在 Java 层过滤（spec 决策 10）：写进 SQL where 会干扰 HNSW 索引走法。
 */
@Service
public class RetrievalService {

    private final KbChunkMapper chunkMapper;
    private final ProviderFacade providerFacade;
    private final int defaultTopK;
    private final double defaultScoreThreshold;

    public RetrievalService(KbChunkMapper chunkMapper, ProviderFacade providerFacade,
                            @Value("${hify.knowledge.retrieval.top-k}") int defaultTopK,
                            @Value("${hify.knowledge.retrieval.score-threshold}") double defaultScoreThreshold) {
        this.chunkMapper = chunkMapper;
        this.providerFacade = providerFacade;
        this.defaultTopK = defaultTopK;
        this.defaultScoreThreshold = defaultScoreThreshold;
    }

    /** 默认参数入口（Facade 委托）：topK/阈值用全局配置。 */
    public List<RetrievedChunk> retrieve(List<Long> datasetIds, String query) {
        return retrieve(datasetIds, query, defaultTopK, defaultScoreThreshold);
    }

    /** 显式参数入口（命中测试端点覆写用）。空 datasetIds 短路，不白跑 embedding API。 */
    public List<RetrievedChunk> retrieve(List<Long> datasetIds, String query, int topK, double scoreThreshold) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            return List.of();
        }
        float[] qvec = providerFacade.getEmbeddingModel().embed(query);
        return chunkMapper.searchByVector(datasetIds, DocumentProcessStore.vectorLiteral(qvec), topK).stream()
                .filter(h -> h.getScore() >= scoreThreshold)
                .map(h -> new RetrievedChunk(h.getId(), h.getDocumentId(), h.getDocumentName(),
                        h.getContent(), h.getScore()))
                .toList();
    }

    int defaultTopK() {
        return defaultTopK;
    }

    double defaultScoreThreshold() {
        return defaultScoreThreshold;
    }
}
```

`server/src/main/java/com/hify/knowledge/service/KnowledgeFacadeImpl.java`：

```java
package com.hify.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.knowledge.api.KnowledgeFacade;
import com.hify.knowledge.api.RetrievedChunk;
import com.hify.knowledge.entity.Dataset;
import com.hify.knowledge.mapper.DatasetMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** {@link KnowledgeFacade} 实现：检索委托 RetrievalService；绑定校验按去重后的 id 数与库内计数比对。 */
@Service
public class KnowledgeFacadeImpl implements KnowledgeFacade {

    private final RetrievalService retrievalService;
    private final DatasetMapper datasetMapper;

    public KnowledgeFacadeImpl(RetrievalService retrievalService, DatasetMapper datasetMapper) {
        this.retrievalService = retrievalService;
        this.datasetMapper = datasetMapper;
    }

    @Override
    public List<RetrievedChunk> retrieve(List<Long> datasetIds, String query) {
        return retrievalService.retrieve(datasetIds, query);
    }

    @Override
    public void validateDatasetIds(List<Long> datasetIds) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            return;
        }
        Set<Long> distinct = new HashSet<>(datasetIds);
        long found = datasetMapper.selectCount(
                new LambdaQueryWrapper<Dataset>().in(Dataset::getId, distinct)); // @TableLogic 自动加 deleted=false
        if (found != distinct.size()) {
            throw new BizException(CommonError.NOT_FOUND, "知识库不存在或已删除");
        }
    }
}
```

`application.yml`（`hify.knowledge` 段 `embedding-batch-size` 之后追加）：

```yaml
    # K4 向量检索全局参数（spec 决策 4：全局固定不做应用级）：取几段 / 相似度阈值（1-余弦距离，低于丢弃）
    retrieval:
      top-k: ${HIFY_KNOWLEDGE_RETRIEVAL_TOP_K:4}
      score-threshold: ${HIFY_KNOWLEDGE_RETRIEVAL_SCORE_THRESHOLD:0.3}
```

- [x] **Step 4: 运行测试通过 + ModularityTests 回归**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='RetrievalServiceTest,KnowledgeFacadeImplTest,ModularityTests,LayerRulesTest'`
Expected: PASS（knowledge 新增 api 顶层类型不破模块规则）

- [x] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/java/com/hify/knowledge/ server/src/main/resources/application.yml server/src/test/java/com/hify/knowledge/
git commit -m "feat(knowledge): KnowledgeFacade.retrieve 检索能力 + 全局 topK/阈值配置（TDD）"
```

---

### Task 4: 命中测试端点 POST /datasets/{id}/retrieve

**Files:**
- Create: `server/src/main/java/com/hify/knowledge/dto/RetrieveTestRequest.java`
- Modify: `server/src/main/java/com/hify/knowledge/service/RetrievalService.java`（追加 retrieveTest）
- Modify: `server/src/main/java/com/hify/knowledge/controller/DatasetController.java`
- Test: `server/src/test/java/com/hify/knowledge/service/RetrievalServiceTest.java`（追加）
- Test: `server/src/test/java/com/hify/knowledge/controller/DatasetControllerTest.java`（追加）

**Interfaces:**
- Consumes: Task 3 的 `RetrievalService.retrieve(ids, query, topK, threshold)` 与 `RetrievedChunk`。
- Produces: `RetrievalService.retrieveTest(Long datasetId, String query, Integer topK, Double scoreThreshold) → List<RetrievedChunk>`（库不存在抛 10005；null 参数落全局默认）；HTTP `POST /api/v1/knowledge/datasets/{id}/retrieve`。

- [x] **Step 1: 写失败测试**

`RetrievalServiceTest` 追加（`RetrievalService` 构造需加 `DatasetMapper`，setUp 同步改——见 Step 3；先按新签名写测试）：

```java
    @Test
    void 命中测试_库不存在_抛10005() {
        when(datasetMapper.selectById(404L)).thenReturn(null);
        BizException e = assertThrows(BizException.class,
                () -> service.retrieveTest(404L, "问题", null, null));
        assertEquals(10005, e.getError().code());
    }

    @Test
    void 命中测试_可选参数为null_落全局默认() {
        when(datasetMapper.selectById(9L)).thenReturn(new Dataset());
        stubEmbedding();
        when(chunkMapper.searchByVector(eq(List.of(9L)), anyString(), eq(4))).thenReturn(List.of());
        service.retrieveTest(9L, "问题", null, null);
        verify(chunkMapper).searchByVector(eq(List.of(9L)), anyString(), eq(4));
    }

    @Test
    void 命中测试_显式参数生效() {
        when(datasetMapper.selectById(9L)).thenReturn(new Dataset());
        stubEmbedding();
        when(chunkMapper.searchByVector(eq(List.of(9L)), anyString(), eq(2)))
                .thenReturn(List.of(hit(1L, "低分", 0.05)));
        assertEquals(1, service.retrieveTest(9L, "问题", 2, 0.0).size());
    }
```

（setUp 中 `datasetMapper = mock(DatasetMapper.class); service = new RetrievalService(chunkMapper, datasetMapper, providerFacade, 4, 0.3);`，import 补 `com.hify.knowledge.entity.Dataset`、`com.hify.knowledge.mapper.DatasetMapper`。）

`DatasetControllerTest` 追加（类上补 `@MockitoBean private RetrievalService retrievalService;`，import `com.hify.knowledge.api.RetrievedChunk`、`com.hify.knowledge.service.RetrievalService`）：

```java
    @Test
    void 命中测试_POST_retrieve_返回命中段_Long转string() throws Exception {
        when(retrievalService.retrieveTest(10L, "退货政策", 2, null))
                .thenReturn(List.of(new RetrievedChunk(1L, 2L, "a.txt", "退货需在7天内", 0.83)));
        mockMvc.perform(post("/api/v1/knowledge/datasets/10/retrieve")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"query\":\"退货政策\",\"topK\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].chunkId").value("1"))
                .andExpect(jsonPath("$.data[0].documentName").value("a.txt"))
                .andExpect(jsonPath("$.data[0].score").value(0.83));
    }

    @Test
    void 命中测试_query空白_10001() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/datasets/10/retrieve")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"query\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 命中测试_topK超上限_10001() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/datasets/10/retrieve")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"query\":\"q\",\"topK\":21}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }
```

- [x] **Step 2: 运行确认失败**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='RetrievalServiceTest,DatasetControllerTest'`
Expected: FAIL（retrieveTest 方法不存在，编译错）

- [x] **Step 3: 写实现**

`server/src/main/java/com/hify/knowledge/dto/RetrieveTestRequest.java`：

```java
package com.hify.knowledge.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 命中测试请求（api-standards 预写范式 POST /datasets/{id}/retrieve）。topK/scoreThreshold 可空=用全局配置，给了本次生效（调参用）。 */
public record RetrieveTestRequest(
        @NotBlank @Size(max = 1000) String query,
        @Min(1) @Max(20) Integer topK,
        @DecimalMin("0.0") @DecimalMax("1.0") Double scoreThreshold) {
}
```

`RetrievalService`：构造函数追加 `DatasetMapper datasetMapper` 参数（字段+赋值，import `com.hify.knowledge.mapper.DatasetMapper`、`com.hify.common.exception.BizException`、`com.hify.common.exception.CommonError`），并追加方法：

```java
    /** 命中测试（排障工具）：不降级，embedding/供应商异常原样抛（12006/12003/12004 前端可见）。 */
    public List<RetrievedChunk> retrieveTest(Long datasetId, String query, Integer topK, Double scoreThreshold) {
        if (datasetMapper.selectById(datasetId) == null) {
            throw new BizException(CommonError.NOT_FOUND, "知识库不存在");
        }
        return retrieve(List.of(datasetId), query,
                topK != null ? topK : defaultTopK,
                scoreThreshold != null ? scoreThreshold : defaultScoreThreshold);
    }
```

（上一 Task 的 `defaultTopK()/defaultScoreThreshold()` 包级访问器若无人使用即删除——本方法直接用字段。）

`DatasetController`：构造注入 `RetrievalService retrievalService`（import `com.hify.knowledge.api.RetrievedChunk`、`com.hify.knowledge.dto.RetrieveTestRequest`、`com.hify.knowledge.service.RetrievalService`、`java.util.List`），追加：

```java
    /** 命中测试（检索调试，不走 LLM）。团队共享读操作，登录即可；不降级，检索故障原样抛。 */
    @PostMapping("/{id}/retrieve")
    public Result<List<RetrievedChunk>> retrieve(@PathVariable Long id,
                                                 @Valid @RequestBody RetrieveTestRequest request) {
        return Result.ok(retrievalService.retrieveTest(id, request.query(), request.topK(), request.scoreThreshold()));
    }
```

- [x] **Step 4: 运行测试通过**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='RetrievalServiceTest,DatasetControllerTest'`
Expected: PASS

- [x] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/java/com/hify/knowledge/ server/src/test/java/com/hify/knowledge/
git commit -m "feat(knowledge): 命中测试端点 POST /datasets/{id}/retrieve（TDD）"
```

---

### Task 5: app 模块绑定知识库（后端）

**Files:**
- Create: `server/src/main/java/com/hify/app/entity/AppDatasetRel.java`
- Create: `server/src/main/java/com/hify/app/mapper/AppDatasetRelMapper.java`
- Modify: `server/src/main/java/com/hify/app/dto/CreateAppRequest.java`、`UpdateAppRequest.java`、`AppResponse.java`
- Modify: `server/src/main/java/com/hify/app/api/AppRuntimeView.java`
- Modify: `server/src/main/java/com/hify/app/service/AppService.java`、`AppFacadeImpl.java`
- Test: `server/src/test/java/com/hify/app/service/AppServiceTest.java`、`AppFacadeImplTest.java`（追加+修复）；`grep -rn "new AppRuntimeView(" server/src` 找到的所有既有用例补第 4 参
- Test: `server/src/test/java/com/hify/app/AppDatasetRelReplaceTest.java`（连库，全量替换语义）

**Interfaces:**
- Consumes: Task 3 的 `KnowledgeFacade.validateDatasetIds`；Task 1 的表与基类。
- Produces: `AppRuntimeView(Long appId, Long modelId, String systemPrompt, List<Long> datasetIds)`（datasetIds 恒非 null，无绑定=空列表）；`CreateAppRequest/UpdateAppRequest` 追加 `@Size(max = 10) List<Long> datasetIds`；`AppResponse` 追加 `List<Long> datasetIds`；实体 `AppDatasetRel(appId, datasetId)` + `AppDatasetRelMapper extends BaseMapper`。

- [x] **Step 1: 写失败测试（AppServiceTest 追加；setUp 构造改 4 参 mock）**

`AppServiceTest` 的 setUp 补 `relMapper = mock(AppDatasetRelMapper.class); knowledgeFacade = mock(KnowledgeFacade.class); service = new AppService(appMapper, providerFacade, relMapper, knowledgeFacade);`，追加用例：

```java
    @Test
    void 创建_带datasetIds_先校验再落绑定行() {
        when(appMapper.insert(any(App.class))).thenAnswer(inv -> {
            inv.getArgument(0, App.class).setId(7L);
            return 1;
        });
        CreateAppRequest req = new CreateAppRequest("知识助手", null, "chat", null, null, List.of(9L, 8L));
        AppResponse resp = service.create(req, admin);
        verify(knowledgeFacade).validateDatasetIds(List.of(9L, 8L));
        verify(relMapper, times(2)).insert(any(AppDatasetRel.class));
        assertEquals(List.of(9L, 8L), resp.datasetIds());
    }

    @Test
    void 创建_datasetIds校验失败_透传异常不落库() {
        doThrow(new BizException(CommonError.NOT_FOUND, "知识库不存在或已删除"))
                .when(knowledgeFacade).validateDatasetIds(List.of(404L));
        CreateAppRequest req = new CreateAppRequest("知识助手", null, "chat", null, null, List.of(404L));
        assertThrows(BizException.class, () -> service.create(req, admin));
        verify(appMapper, never()).insert(any(App.class));
    }

    @Test
    void 更新_全量替换绑定_先软删后插入() {
        stubExistingApp(7L); // 既有辅助：selectById 返回 chat 型 app（照本文件既有写法）
        UpdateAppRequest req = new UpdateAppRequest("改名", null, null, null, List.of(9L));
        service.update(7L, req, admin);
        InOrder inOrder = inOrder(relMapper);
        inOrder.verify(relMapper).delete(any());
        inOrder.verify(relMapper).insert(any(AppDatasetRel.class));
    }

    @Test
    void 更新_datasetIds为null_清空绑定_响应空列表() {
        stubExistingApp(7L);
        UpdateAppRequest req = new UpdateAppRequest("改名", null, null, null, null);
        AppResponse resp = service.update(7L, req, admin);
        verify(relMapper).delete(any());
        verify(relMapper, never()).insert(any(AppDatasetRel.class));
        assertEquals(List.of(), resp.datasetIds());
    }

    @Test
    void 创建_重复datasetIds_去重后落库() {
        when(appMapper.insert(any(App.class))).thenAnswer(inv -> {
            inv.getArgument(0, App.class).setId(7L);
            return 1;
        });
        CreateAppRequest req = new CreateAppRequest("知识助手", null, "chat", null, null, List.of(9L, 9L));
        service.create(req, admin);
        verify(relMapper, times(1)).insert(any(AppDatasetRel.class));
    }
```

（`stubExistingApp`、`admin` 等辅助按 AppServiceTest 既有代码实名对齐；若无同名辅助则照该文件已有 stub 模式内联。）

`AppFacadeImplTest` 追加/修复：构造补 `relMapper`；既有用例断言的 `AppRuntimeView` 构造补第 4 参 `List.of()`；追加：

```java
    @Test
    void findRunnableChatApp_带绑定的知识库ids() {
        // stub app 可运行（照既有用例），rel 表返回两行
        when(relMapper.selectList(any())).thenReturn(List.of(rel(7L, 9L), rel(7L, 8L)));
        Optional<AppRuntimeView> view = facade.findRunnableChatApp(7L);
        assertEquals(List.of(9L, 8L), view.orElseThrow().datasetIds());
    }

    private static AppDatasetRel rel(Long appId, Long datasetId) {
        AppDatasetRel r = new AppDatasetRel();
        r.setAppId(appId);
        r.setDatasetId(datasetId);
        return r;
    }
```

连库测试 `server/src/test/java/com/hify/app/AppDatasetRelReplaceTest.java`（全量替换真库语义：软删+重插不撞唯一索引）：

```java
package com.hify.app;

import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.dto.UpdateAppRequest;
import com.hify.app.service.AppService;
import com.hify.infra.security.CurrentUser;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 绑定全量替换的真库测试：软删旧行 + 重插同一 dataset 不撞部分唯一索引（mock 测不到索引行为）。 */
class AppDatasetRelReplaceTest extends PgIntegrationTest {

    @Autowired
    private AppService appService;
    @Autowired
    private JdbcTemplate jdbc;

    private final CurrentUser admin = new CurrentUser(1L, "admin", CurrentUser.ROLE_ADMIN);

    @Test
    void 编辑重复保存同一批绑定_软删加重插_不撞唯一索引() {
        Long dsId = jdbc.queryForObject(
                "insert into dataset(name, owner_id) values ('K4绑定库', 1) returning id", Long.class);
        AppResponse created = appService.create(
                new CreateAppRequest("K4绑定应用", null, "chat", null, null, List.of(dsId)), admin);
        // 两次保存同一绑定：第一次软删后重插，第二次亦然——部分唯一索引只看未删行，不应报冲突
        appService.update(created.id(), new UpdateAppRequest("K4绑定应用", null, null, null, List.of(dsId)), admin);
        appService.update(created.id(), new UpdateAppRequest("K4绑定应用", null, null, null, List.of(dsId)), admin);
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from app_dataset_rel where app_id = ? and deleted = false",
                Integer.class, created.id()));
        assertEquals(List.of(dsId), appService.get(created.id()).datasetIds());
    }
}
```

- [x] **Step 2: 运行确认失败（编译错：DTO 无 datasetIds、实体不存在）**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='AppServiceTest,AppFacadeImplTest,AppDatasetRelReplaceTest'`
Expected: FAIL（compilation error）

- [x] **Step 3: 写实现**

`server/src/main/java/com/hify/app/entity/AppDatasetRel.java`：

```java
package com.hify.app.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/** 应用↔知识库关系表 {@code app_dataset_rel} 映射实体。dataset_id 跨模块弱引用；更新=全量替换（软删+插新）。 */
@TableName("app_dataset_rel")
public class AppDatasetRel extends BaseEntity {

    private Long appId;
    private Long datasetId;

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
}
```

`server/src/main/java/com/hify/app/mapper/AppDatasetRelMapper.java`：

```java
package com.hify.app.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.app.entity.AppDatasetRel;
import org.apache.ibatis.annotations.Mapper;

/** app_dataset_rel 表访问，纯框架 CRUD。 */
@Mapper
public interface AppDatasetRelMapper extends BaseMapper<AppDatasetRel> {
}
```

DTO 三处（照原文件注释风格补一行说明）：
- `CreateAppRequest` 末位追加字段 `@Size(max = 10) List<Long> datasetIds`（import `java.util.List`；record 注释补「datasetIds 可空=不绑知识库，上限 10」）。
- `UpdateAppRequest` 同样末位追加 `@Size(max = 10) List<Long> datasetIds`。
- `AppResponse` 在 `config` 之后、`ownerId` 之前插入 `List<Long> datasetIds`（Long 元素全局序列化为 string）。

`AppRuntimeView`：

```java
package com.hify.app.api;

import java.util.List;

/**
 * 应用运行时视图（跨模块）：conversation 取它发起对话。
 * 位于 api 顶层包（@NamedInterface("api")），不引用 app.api.dto——避免跨模块消费未暴露的子包类型。
 * modelId 必非空；systemPrompt 为应用人设（可空）；datasetIds 为绑定的知识库（恒非 null，无绑定=空列表，K4 起）。
 */
public record AppRuntimeView(Long appId, Long modelId, String systemPrompt, List<Long> datasetIds) {
}
```

`AppService` 改动（构造注入 `AppDatasetRelMapper relMapper`、`KnowledgeFacade knowledgeFacade`，import `com.hify.knowledge.api.KnowledgeFacade`、`com.hify.app.entity.AppDatasetRel`、`java.util.Map`、`java.util.stream.Collectors`）：

```java
    // create(...)：assertModelUsableIfPresent 之后、insert 之前加：
        List<Long> datasetIds = req.datasetIds() == null ? List.of() : req.datasetIds();
        knowledgeFacade.validateDatasetIds(datasetIds);
    // insert 成功后（catch 之后）加：
        replaceDatasetBindings(entity.getId(), datasetIds);
    // return 改为：
        return toResponse(entity, modelNameOf(entity.getModelId()), modelUsableOf(entity.getModelId()),
                datasetIds.stream().distinct().toList());

    // update(...)：assertModelUsableIfPresent 之后加（type 不可改，现存应用均 chat；防御性判断照 spec「workflow 型忽略」）：
        List<Long> datasetIds = req.datasetIds() == null ? List.of() : req.datasetIds();
        if (AppType.CHAT.value().equals(app.getType())) {
            knowledgeFacade.validateDatasetIds(datasetIds);
        }
    // updateById 成功后加：
        if (AppType.CHAT.value().equals(app.getType())) {
            replaceDatasetBindings(app.getId(), datasetIds);
        }
    // return 改为：
        return toResponse(app, modelNameOf(app.getModelId()), modelUsableOf(app.getModelId()),
                datasetIdsOf(app.getId()));

    // get(...) 的 return 改为：
        return toResponse(app, modelNameOf(app.getModelId()), modelUsableOf(app.getModelId()),
                datasetIdsOf(app.getId()));

    // page(...)：names/usable 两行之后加批量绑定查询，list 映射补第 4 参：
        Map<Long, List<Long>> bindings = datasetIdsByApp(records.stream().map(App::getId).toList());
        // .map(a -> toResponse(a, ..., bindings.getOrDefault(a.getId(), List.of())))

    /** 全量替换绑定：软删该应用全部关系行 + 插入新勾选（去重）。调用方均在 @Transactional 内。 */
    private void replaceDatasetBindings(Long appId, List<Long> datasetIds) {
        relMapper.delete(new LambdaQueryWrapper<AppDatasetRel>().eq(AppDatasetRel::getAppId, appId));
        for (Long dsId : datasetIds.stream().distinct().toList()) {
            AppDatasetRel rel = new AppDatasetRel();
            rel.setAppId(appId);
            rel.setDatasetId(dsId);
            relMapper.insert(rel);
        }
    }

    /** 单应用的绑定知识库 ids（按绑定先后稳定排序）。 */
    private List<Long> datasetIdsOf(Long appId) {
        return relMapper.selectList(new LambdaQueryWrapper<AppDatasetRel>()
                        .eq(AppDatasetRel::getAppId, appId).orderByAsc(AppDatasetRel::getId))
                .stream().map(AppDatasetRel::getDatasetId).toList();
    }

    /** 批量取绑定（列表页防 N+1：一页一查）。 */
    private Map<Long, List<Long>> datasetIdsByApp(List<Long> appIds) {
        if (appIds.isEmpty()) {
            return Map.of();
        }
        return relMapper.selectList(new LambdaQueryWrapper<AppDatasetRel>()
                        .in(AppDatasetRel::getAppId, appIds).orderByAsc(AppDatasetRel::getId))
                .stream().collect(Collectors.groupingBy(AppDatasetRel::getAppId,
                        Collectors.mapping(AppDatasetRel::getDatasetId, Collectors.toList())));
    }

    // toResponse 签名追加第 4 参 List<Long> datasetIds，构造 AppResponse 时在 config 后传入。
```

`AppFacadeImpl`：构造注入 `AppDatasetRelMapper relMapper`（import `com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper`、`com.hify.app.entity.AppDatasetRel`、`java.util.List`），return 前加：

```java
        List<Long> datasetIds = relMapper.selectList(new LambdaQueryWrapper<AppDatasetRel>()
                        .eq(AppDatasetRel::getAppId, app.getId()).orderByAsc(AppDatasetRel::getId))
                .stream().map(AppDatasetRel::getDatasetId).toList();
        return Optional.of(new AppRuntimeView(app.getId(), app.getModelId(), systemPrompt, datasetIds));
```

- [x] **Step 4: 修复全仓 AppRuntimeView / AppResponse 构造点**

Run: `cd /home/wang/playlab/hify/server && grep -rn "new AppRuntimeView(\|new AppResponse(" src --include=*.java`
对 grep 出的每一处（主代码已改的除外，预期主要是 `ConversationServiceTest.stubRunnableApp`、`AppFacadeImplTest`、app controller 测试 fixture）：`AppRuntimeView` 补第 4 参 `List.of()`（本 Task 不改 conversation 行为，Task 6 再引入非空场景）；`AppResponse` 在 config 后补 `List.of()`。

- [x] **Step 5: 运行测试通过 + 模块回归**

Run: `cd /home/wang/playlab/hify/server && mvn test`
Expected: 全绿（含 ModularityTests——app→knowledge::api 白名单既有；AppDatasetRelReplaceTest 连库通过）

- [x] **Step 6: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src
git commit -m "feat(app): 应用绑定知识库（校验+全量替换+运行时视图带 datasetIds，TDD）"
```

---

### Task 6: conversation 检索注入 + 降级

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`

**Interfaces:**
- Consumes: Task 3 的 `KnowledgeFacade.retrieve(List<Long>, String)`；Task 5 的 `AppRuntimeView.datasetIds()`。
- Produces: 无新对外接口（send/sendStream 行为增强：绑库时提示词尾部拼参考资料，检索失败记 warn 降级）。

- [x] **Step 1: 写失败测试**

`ConversationServiceTest`：setUp 补 `knowledgeFacade = mock(KnowledgeFacade.class); service = new ConversationService(appFacade, providerFacade, chatInvoker, store, quotaGuard, knowledgeFacade);`；`stubRunnableApp` 改为透传 datasetIds：

```java
    private void stubRunnableApp(String systemPrompt) {
        stubRunnableApp(systemPrompt, List.of());
    }

    private void stubRunnableApp(String systemPrompt, List<Long> datasetIds) {
        when(appFacade.findRunnableChatApp(eq(7L)))
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, systemPrompt, datasetIds)));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
    }
```

追加用例（import `com.hify.knowledge.api.KnowledgeFacade`、`com.hify.knowledge.api.RetrievedChunk`、`org.mockito.ArgumentCaptor`、`static org.mockito.Mockito.verifyNoInteractions` 等；openTurn/invoke 的 stub 写法照本文件既有 send 用例）：

```java
    @Test
    void 绑库_检索命中_系统提示词尾部拼参考资料() {
        stubRunnableApp("你是客服", List.of(9L));
        stubTurnAndReply(); // 照既有用例：openTurn 返回 TurnContext、chatInvoker.invoke 返回 LlmReply、appendAssistant 返回 Message
        when(knowledgeFacade.retrieve(List.of(9L), "退货政策"))
                .thenReturn(List.of(new RetrievedChunk(1L, 2L, "a.txt", "退货需在7天内", 0.83)));
        service.send(7L, null, "退货政策", member);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatInvoker).invoke(eq(chatClient), prompt.capture(), any());
        assertTrue(prompt.getValue().startsWith("你是客服"));
        assertTrue(prompt.getValue().contains("参考资料"));
        assertTrue(prompt.getValue().contains("[1] 退货需在7天内"));
    }

    @Test
    void 绑库_原提示词为空_直接以参考资料开头() {
        stubRunnableApp(null, List.of(9L));
        stubTurnAndReply();
        when(knowledgeFacade.retrieve(List.of(9L), "退货政策"))
                .thenReturn(List.of(new RetrievedChunk(1L, 2L, "a.txt", "退货需在7天内", 0.83)));
        service.send(7L, null, "退货政策", member);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatInvoker).invoke(eq(chatClient), prompt.capture(), any());
        assertTrue(prompt.getValue().startsWith("请优先依据下列参考资料"));
    }

    @Test
    void 绑库_检索抛异常_降级用原提示词且正常回答() {
        stubRunnableApp("你是客服", List.of(9L));
        stubTurnAndReply();
        when(knowledgeFacade.retrieve(any(), any())).thenThrow(new RuntimeException("供应商超时"));
        service.send(7L, null, "退货政策", member); // 不抛异常即降级成功
        verify(chatInvoker).invoke(eq(chatClient), eq("你是客服"), any());
    }

    @Test
    void 绑库_命中为空_提示词原样不拼空壳() {
        stubRunnableApp("你是客服", List.of(9L));
        stubTurnAndReply();
        when(knowledgeFacade.retrieve(List.of(9L), "退货政策")).thenReturn(List.of());
        service.send(7L, null, "退货政策", member);
        verify(chatInvoker).invoke(eq(chatClient), eq("你是客服"), any());
    }

    @Test
    void 未绑库_不调检索() {
        stubRunnableApp("你是客服");
        stubTurnAndReply();
        service.send(7L, null, "你好", member);
        verifyNoInteractions(knowledgeFacade);
    }
```

（`stubTurnAndReply` 为本文件既有 stub 逻辑的提取；若既有用例是内联 stub，就照抄内联。既有全部用例的 `new ConversationService(...)` 构造与 `AppRuntimeView` 构造同步修复。）

- [x] **Step 2: 运行确认失败**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=ConversationServiceTest`
Expected: FAIL（构造签名不匹配，编译错）

- [x] **Step 3: 写实现**

`ConversationService`：构造注入 `KnowledgeFacade knowledgeFacade`（import `com.hify.knowledge.api.KnowledgeFacade`、`com.hify.knowledge.api.RetrievedChunk`、`org.slf4j.Logger`、`org.slf4j.LoggerFactory`、`org.springframework.util.StringUtils`），加类字段 `private static final Logger log = LoggerFactory.getLogger(ConversationService.class);` 与常量、私有方法：

```java
    /** 检索注入的提示词头（K4；引用来源展示留下轮，此处只拼内容不拼出处）。 */
    private static final String KNOWLEDGE_PROMPT_HEADER =
            "请优先依据下列参考资料回答用户问题；资料未覆盖时可依据自身知识回答。\n参考资料：";

    /**
     * 绑库应用：按当前消息检索并把命中段拼进系统提示词尾部；未绑/无命中原样返回。
     * 检索任何失败（未配 embedding 模型/供应商超时熔断）降级继续答（spec 决策 5）——只记 warn，不影响本轮对话。
     * 调用点在事务 A 之后、LLM 调用之前（事务间隙，与「事务内禁外部 IO」红线一致）。
     */
    private String augmentWithKnowledge(AppRuntimeView app, String content) {
        if (app.datasetIds() == null || app.datasetIds().isEmpty()) {
            return app.systemPrompt();
        }
        try {
            List<RetrievedChunk> chunks = knowledgeFacade.retrieve(app.datasetIds(), content);
            if (chunks.isEmpty()) {
                return app.systemPrompt();
            }
            StringBuilder sb = new StringBuilder();
            if (StringUtils.hasText(app.systemPrompt())) {
                sb.append(app.systemPrompt()).append("\n\n");
            }
            sb.append(KNOWLEDGE_PROMPT_HEADER);
            for (int i = 0; i < chunks.size(); i++) {
                sb.append('\n').append('[').append(i + 1).append("] ").append(chunks.get(i).content());
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("知识检索失败，本轮降级为无参考资料回答 appId={}", app.appId(), e);
            return app.systemPrompt();
        }
    }
```

`send`：`ChatClient chatClient = ...` 之后、`chatInvoker.invoke` 之前加 `String effectivePrompt = augmentWithKnowledge(app, content);`，invoke 第 2 参改 `effectivePrompt`。
`sendStream`：同位置加同一行，`chatInvoker.invokeStream(chatClient, effectivePrompt, turn.window())`。

- [x] **Step 4: 运行测试通过 + conversation 全量回归**

Run: `cd /home/wang/playlab/hify/server && mvn test`
Expected: 全绿（ModularityTests：conversation→knowledge::api 白名单既有）

- [x] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src
git commit -m "feat(conversation): 绑库应用检索命中拼系统提示词，失败降级继续答（TDD）"
```

---

### Task 7: 前端 api 层 + 类型

**Files:**
- Modify: `web/src/types/app.ts`、`web/src/types/knowledge.ts`、`web/src/api/knowledge.ts`
- Test: `web/src/api/__tests__/knowledge.spec.ts`、`web/src/api/__tests__/app.spec.ts`（fixture 修）
- Modify: `grep -rln "modelUsable" web/src --include=*.spec.ts` 命中的所有 `App` fixture 补 `datasetIds: []`

**Interfaces:**
- Consumes: Task 4/5 的后端契约。
- Produces: `retrieveTest(datasetId: string, body: RetrieveTestForm) → Promise<RetrievedChunk[]>`；`App`/`AppForm` 均含 `datasetIds: string[]`；`RetrievedChunk { chunkId, documentId, documentName, content, score }`。

- [x] **Step 1: 写失败测试**

`web/src/api/__tests__/knowledge.spec.ts` 追加（import 补 `retrieveTest`）：

```ts
  it('retrieveTest → POST /knowledge/datasets/{id}/retrieve + body', () => {
    retrieveTest('10', { query: '退货政策', topK: 2 })
    expect(request.post).toHaveBeenCalledWith('/knowledge/datasets/10/retrieve', {
      query: '退货政策',
      topK: 2,
    })
  })
```

`web/src/api/__tests__/app.spec.ts`：既有 `AppForm` fixture 补 `datasetIds: []`，并在 createApp 断言的期望对象里加 `datasetIds: []`（提交载荷透传验证）。

- [x] **Step 2: 运行确认失败**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/api/__tests__/knowledge.spec.ts src/api/__tests__/app.spec.ts`
Expected: FAIL（retrieveTest 未导出）

- [x] **Step 3: 写实现**

`web/src/types/knowledge.ts` 末尾追加：

```ts
/** 命中测试请求（对齐后端 RetrieveTestRequest）。topK/scoreThreshold 缺省=走后端全局配置。 */
export interface RetrieveTestForm {
  query: string
  topK?: number
  scoreThreshold?: number
}

/** 检索命中段（对齐后端 RetrievedChunk）。chunkId/documentId 为 string（Long 序列化）；score=相似度，越大越相关。 */
export interface RetrievedChunk {
  chunkId: string
  documentId: string
  documentName: string
  content: string
  score: number
}
```

`web/src/api/knowledge.ts`（import type 补 `RetrieveTestForm, RetrievedChunk`），`updateDataset` 与 `deleteDataset` 之间或文件尾部追加：

```ts
/** 命中测试（检索调试，不走 LLM）。后端：POST /api/v1/knowledge/datasets/{id}/retrieve */
export function retrieveTest(datasetId: string, body: RetrieveTestForm) {
  return request.post<RetrievedChunk[]>(`${BASE}/${datasetId}/retrieve`, body)
}
```

`web/src/types/app.ts`：`App` 的 `config` 行后加 `datasetIds: string[]`（注释「绑定的知识库 ids（K4 起）」）；`AppForm` 同样加 `datasetIds: string[]`。

- [x] **Step 4: 修全仓 App fixture**

Run: `cd /home/wang/playlab/hify/web && grep -rln "modelUsable" src --include='*.spec.ts' --include='*.ts' --include='*.vue'`
对命中文件里每个 `App` 对象字面量补 `datasetIds: []`（AppList.spec 的 MINE/OTHERS/WITH_MODEL/NAMED、chat 相关 spec 的 fixture 等）。

- [x] **Step 5: 运行测试通过 + 类型检查**

Run: `cd /home/wang/playlab/hify/web && pnpm test && pnpm build`
Expected: vitest 全绿；vue-tsc 无类型错误（漏改的 fixture 在这一步暴露）

- [x] **Step 6: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src
git commit -m "feat(web): 命中测试 api + App 类型带 datasetIds（TDD）"
```

---

### Task 8: AppList 弹窗「关联知识库」多选

**Files:**
- Modify: `web/src/views/app/AppList.vue`
- Test: `web/src/views/app/__tests__/AppList.spec.ts`

**Interfaces:**
- Consumes: Task 7 的 `AppForm.datasetIds`；既有 `listDatasets`（`@/api/knowledge`）。
- Produces: 弹窗 `[data-test="form-datasets"]` 多选；提交载荷带 `datasetIds`。

- [x] **Step 1: 写失败测试**

`AppList.spec.ts`：顶部补 mock 与 fixture：

```ts
import { listDatasets } from '@/api/knowledge'
import { updateApp } from '@/api/app'  // 并入既有 import
vi.mock('@/api/knowledge', () => ({ listDatasets: vi.fn() }))

const DS = {
  id: '9', name: '客服知识库', description: null, ownerId: '7',
  createTime: '2026-07-02T10:00:00+08:00', updateTime: '2026-07-02T10:00:00+08:00',
}
```

beforeEach 补 `vi.mocked(listDatasets).mockResolvedValue({ list: [DS], total: '1', page: '1', size: '100' })`。
新 fixture：`const BOUND: App = { ...MINE, id: '6', name: '绑库应用', datasetIds: ['9'] }`。
追加用例：

```ts
  it('编辑：回显已绑知识库并提交载荷带 datasetIds', async () => {
    vi.mocked(listApps).mockResolvedValue(page([BOUND]))
    vi.mocked(updateApp).mockResolvedValue(BOUND)
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="edit-6"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(updateApp).toHaveBeenCalledWith('6', expect.objectContaining({ datasetIds: ['9'] }))
  })

  it('编辑：已删除的知识库以禁用项回显', async () => {
    vi.mocked(listApps).mockResolvedValue(page([{ ...BOUND, datasetIds: ['404'] }]))
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="edit-6"]').trigger('click')
    await flushPromises()
    const gone = wrapper.findAllComponents(ElOption).find((o) => o.props('label') === '已删除的知识库')
    expect(gone).toBeTruthy()
    expect(gone!.props('disabled')).toBe(true)
  })

  it('新建：提交载荷 datasetIds 为空数组', async () => {
    vi.mocked(createApp).mockResolvedValue(MINE)
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="create-open"]').trigger('click')
    await wrapper.find('[data-test="form-name"] input').setValue('新应用')
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createApp).toHaveBeenCalledWith(expect.objectContaining({ datasetIds: [] }))
  })
```

（选择器/交互写法若与文件内既有用例惯例不同，以既有用例为准微调，断言目标不变。）

- [x] **Step 2: 运行确认失败**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/app/__tests__/AppList.spec.ts`
Expected: FAIL（form 无 datasetIds、无 form-datasets 控件）

- [x] **Step 3: 写实现（AppList.vue）**

script 部分：

```ts
// import 区补：
import { listDatasets } from '@/api/knowledge'
import type { Dataset } from '@/types/knowledge'

// form 初始化改：
const form = reactive<AppForm>({
  name: '', description: '', modelId: null, config: { systemPrompt: '' }, datasetIds: [],
})

// 模型选项区之后加：
// 知识库选项（团队共享全员可见），打开弹窗时刷新；一页 100 够内部团队用
const datasetOptions = ref<Dataset[]>([])
async function loadDatasetOptions() {
  try {
    const res = await listDatasets({ page: 1, size: 100 })
    datasetOptions.value = res.list
  } catch {
    /* 失败由 request 拦截器统一 toast；下拉留空 */
  }
}

/** 下拉选项：现存知识库 + （编辑态）已绑但已删除的库作禁用项，避免裸露 id（同模型下拉手法）。 */
const datasetSelectOptions = computed(() => {
  const opts = datasetOptions.value.map((d) => ({ value: d.id, label: d.name, disabled: false }))
  for (const id of form.datasetIds) {
    if (!datasetOptions.value.some((d) => d.id === id)) {
      opts.unshift({ value: id, label: '已删除的知识库', disabled: true })
    }
  }
  return opts
})

// openCreate 补：form.datasetIds = []、loadDatasetOptions()
// openEdit 补：form.datasetIds = [...row.datasetIds]、loadDatasetOptions()
```

template（「系统提示词」表单项之前插入）：

```vue
        <el-form-item label="关联知识库">
          <el-select
            v-model="form.datasetIds"
            data-test="form-datasets"
            multiple
            clearable
            placeholder="选择知识库（可选）"
            class="app-list__model-select"
          >
            <el-option
              v-for="o in datasetSelectOptions"
              :key="o.value"
              :value="o.value"
              :label="o.label"
              :disabled="o.disabled"
            />
          </el-select>
          <div class="app-list__hint">绑定后，该应用回答会参考所选知识库内容</div>
        </el-form-item>
```

style 补：

```scss
.app-list__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
  margin-top: 4px;
}
```

- [x] **Step 4: 运行测试通过（含既有用例回归）**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/app/__tests__/AppList.spec.ts`
Expected: PASS

- [x] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src
git commit -m "feat(web): 应用弹窗关联知识库多选（已删库禁用项回显，TDD）"
```

---

### Task 9: DatasetDetail 命中测试弹窗

**Files:**
- Modify: `web/src/views/knowledge/DatasetDetail.vue`
- Test: `web/src/views/knowledge/__tests__/DatasetDetail.spec.ts`

**Interfaces:**
- Consumes: Task 7 的 `retrieveTest` / `RetrievedChunk`。
- Produces: `[data-test="retrieve-open"]` 按钮 + 弹窗（`retrieve-query`/`retrieve-topk`/`retrieve-threshold`/`retrieve-run`），结果卡片含分数/文档名/内容。

- [x] **Step 1: 写失败测试**

`DatasetDetail.spec.ts`：`vi.mock('@/api/knowledge', ...)` 工厂补 `retrieveTest: vi.fn(),`，import 补 `retrieveTest`。追加：

```ts
  it('命中测试：输入问题→调 api 并渲染分数与文档名', async () => {
    vi.mocked(retrieveTest).mockResolvedValue([
      { chunkId: '1', documentId: '2', documentName: 'a.txt', content: '退货需在7天内', score: 0.83 },
    ])
    const wrapper = await mountPage()
    await wrapper.find('[data-test="retrieve-open"]').trigger('click')
    await wrapper.find('[data-test="retrieve-query"] input').setValue('退货政策')
    await wrapper.find('[data-test="retrieve-run"]').trigger('click')
    await flushPromises()
    expect(retrieveTest).toHaveBeenCalledWith('1', { query: '退货政策', topK: undefined, scoreThreshold: undefined })
    expect(wrapper.text()).toContain('a.txt')
    expect(wrapper.text()).toContain('0.83')
    expect(wrapper.text()).toContain('退货需在7天内')
  })

  it('命中测试：空结果显示提示', async () => {
    vi.mocked(retrieveTest).mockResolvedValue([])
    const wrapper = await mountPage()
    await wrapper.find('[data-test="retrieve-open"]').trigger('click')
    await wrapper.find('[data-test="retrieve-query"] input').setValue('无关问题')
    await wrapper.find('[data-test="retrieve-run"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('无命中分段')
  })

  it('命中测试：问题为空不发请求', async () => {
    const wrapper = await mountPage()
    await wrapper.find('[data-test="retrieve-open"]').trigger('click')
    await wrapper.find('[data-test="retrieve-run"]').trigger('click')
    await flushPromises()
    expect(retrieveTest).not.toHaveBeenCalled()
  })
```

（mountPage 为该文件既有辅助；datasetId 以其 vue-router mock 的 params.id 为准，断言第一参同步调整。）

- [x] **Step 2: 运行确认失败**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/knowledge/__tests__/DatasetDetail.spec.ts`
Expected: FAIL（无 retrieve-open 元素）

- [x] **Step 3: 写实现（DatasetDetail.vue）**

script 补（import 区补 `retrieveTest` 与 `RetrievedChunk` 类型）：

```ts
// —— 命中测试弹窗（检索调试，不走 LLM；12006/12003 等错误由拦截器 toast，不吞）——
const retrieveDialogVisible = ref(false)
const retrieveQuery = ref('')
const retrieveTopK = ref<number | undefined>()
const retrieveThreshold = ref<number | undefined>()
const retrieving = ref(false)
const retrieveTried = ref(false)
const hits = ref<RetrievedChunk[]>([])

function openRetrieveTest() {
  retrieveDialogVisible.value = true
}

async function onRetrieveRun() {
  const q = retrieveQuery.value.trim()
  if (!q) {
    ElMessage.error('请输入测试问题')
    return
  }
  retrieving.value = true
  try {
    hits.value = await retrieveTest(datasetId, {
      query: q,
      topK: retrieveTopK.value || undefined,
      scoreThreshold: retrieveThreshold.value ?? undefined,
    })
    retrieveTried.value = true
  } catch {
    /* 未配 embedding 模型/供应商故障由拦截器 toast——排障工具要暴露真实错误 */
  } finally {
    retrieving.value = false
  }
}
```

template：PageHeader 内「返回列表」按钮后加：

```vue
      <el-button data-test="retrieve-open" @click="openRetrieveTest">命中测试</el-button>
```

页面根部（el-drawer 之后）加弹窗：

```vue
    <el-dialog v-model="retrieveDialogVisible" title="命中测试" width="640">
      <div class="dataset-detail__retrieve-form">
        <el-input
          v-model="retrieveQuery"
          data-test="retrieve-query"
          placeholder="输入一句话，查看检索会命中哪些分段（不走 LLM）"
          maxlength="1000"
          @keyup.enter="onRetrieveRun"
        />
        <el-input-number
          v-model="retrieveTopK"
          data-test="retrieve-topk"
          :min="1"
          :max="20"
          placeholder="topK"
          controls-position="right"
        />
        <el-input-number
          v-model="retrieveThreshold"
          data-test="retrieve-threshold"
          :min="0"
          :max="1"
          :step="0.05"
          placeholder="阈值"
          controls-position="right"
        />
        <el-button type="primary" data-test="retrieve-run" :loading="retrieving" @click="onRetrieveRun"
          >测试</el-button
        >
      </div>
      <div class="dataset-detail__retrieve-hint">topK/阈值留空则使用系统全局配置</div>
      <template v-if="retrieveTried">
        <div v-if="hits.length === 0" class="dataset-detail__retrieve-empty">
          无命中分段（可尝试降低阈值或换个问法）
        </div>
        <div v-for="h in hits" :key="h.chunkId" class="dataset-detail__chunk">
          <div class="dataset-detail__chunk-pos">
            <el-tag size="small" type="success">{{ h.score.toFixed(2) }}</el-tag>
            <span class="dataset-detail__retrieve-doc">{{ h.documentName }}</span>
          </div>
          <div class="dataset-detail__chunk-content">{{ h.content }}</div>
        </div>
      </template>
    </el-dialog>
```

style 补：

```scss
.dataset-detail__retrieve-form {
  display: flex;
  gap: $spacing-sm;
  align-items: center;
}
.dataset-detail__retrieve-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 4px 0 $spacing-md;
}
.dataset-detail__retrieve-empty {
  color: var(--el-text-color-secondary);
  padding: $spacing-md 0;
}
.dataset-detail__retrieve-doc {
  margin-left: $spacing-sm;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
```

- [x] **Step 4: 运行测试通过 + 前端全量回归**

Run: `cd /home/wang/playlab/hify/web && pnpm test && pnpm build`
Expected: 全绿 + 类型检查通过

- [x] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src
git commit -m "feat(web): 知识库详情页命中测试弹窗（分数/文档名/空态，TDD）"
```

---

### Task 10: 文档入档 + 全量回归

**Files:**
- Modify: `docs/architecture/database-standards.md`（§2.1 一句）
- Modify: `CLAUDE.md`（技术栈一处措辞）
- Verify: `docs/architecture/data-model.md`（app_dataset_rel 已在表清单，无需改动，核对即可）

**Interfaces:** 无代码接口；拍板结论入档（CLAUDE.md 行为指令要求）。

- [x] **Step 1: 改 database-standards §2.1**

原文：

```
  `dataset_id` 必有 b-tree 索引（原则 1）；通过 Spring AI `VectorStore` 的 filter 表达，不手写。
```

改为：

```
  `dataset_id` 必有 b-tree 索引（原则 1）。检索 SQL 在 Mapper 手写注解 SQL（K4 拍板：kb_chunk
  自建表与 Spring AI `PgVectorStore` 表结构不兼容，Advisor 抽象对单一消费方无收益）；多库过滤用
  MyBatis foreach `in (...)`，与 `any(?)` 等价。相似度阈值在 Java 层过滤，不进 where（避免干扰
  HNSW 索引走法）。
```

- [x] **Step 2: 改 CLAUDE.md 技术栈措辞**

「技术栈」段原文 `Spring AI（多模型ChatClient/Tool Calling/RAG Advisors/VectorStore）` 改为 `Spring AI（多模型ChatClient/Tool Calling/EmbeddingModel；RAG 检索为手写 SQL+手动拼提示词，不用 VectorStore/Advisors，见 database-standards §2.1）`。

- [x] **Step 3: 后端 + 前端全量回归**

Run: `cd /home/wang/playlab/hify/server && mvn verify`（判定看退出码，不 grep）
Run: `cd /home/wang/playlab/hify/web && pnpm test && pnpm build`
Expected: 全部通过

- [x] **Step 4: Commit**

```bash
cd /home/wang/playlab/hify && git add docs/architecture/database-standards.md CLAUDE.md
git commit -m "docs(architecture): K4 拍板入档——检索手写 SQL 替代 VectorStore/Advisor 口径"
```

- [x] **Step 5: 手动验收清单（用户执行，照 spec §7.4）**

1. 建知识库传文档至 ready → 应用绑该库 → 聊天问文档内问题（回答引用资料内容）。
2. 问无关问题：正常回答不硬凑。
3. 命中测试同款问题：看到命中段与分数；调高阈值观察减少。
4. 解绑后再问：回答不再引用。
5. 停用 embedding 模型后聊天：照常回答，server 日志有「知识检索失败」warn；命中测试页报错可见。
6. member 账号全流程：权限口径与 owner/Admin 一致（绑定编辑仅 owner/Admin）。
7. psql 抽查：`select count(*) from app_dataset_rel where deleted = false;` 与界面绑定一致。

---

## Self-Review 记录

- **Spec 覆盖**：决策 1-12 逐条对应 Task 1(表/基建)、2(SQL/留账)、3(Facade/配置)、4(命中测试)、5(绑定)、6(注入降级)、7-9(前端)、10(文档)。「明确不做」清单无任务触碰。✅
- **占位符**：无 TBD；两处「照既有用例实名对齐」是对执行者的核对指令而非缺内容（fixture 辅助名只能现场确认）。✅
- **类型一致性**：`searchByVector(List<Long>, String, int)`、`retrieve(List<Long>, String[, int, double])`、`retrieveTest(Long, String, Integer, Double)`、`AppRuntimeView(…, List<Long> datasetIds)` 各任务间引用一致；前端 `RetrievedChunk.chunkId: string` 与后端 Long→string 契约一致。✅

## 执行记录

- Task 1：完成 V18 关系表与 Testcontainers 连库基建；红灯覆盖迁移缺失/连库基类，绿灯后 `mvn test -Dapi.version=1.54` 393 tests pass；提交 `ccf0980`。
- Task 2：完成 pgvector 手写余弦检索 SQL 与 K3 留账真库直测；红灯覆盖缺失 Mapper/API，绿灯后目标用例通过；提交 `5b12970`。
- Task 3：完成 `KnowledgeFacade.retrieve`、全局 topK/阈值配置与过滤；红灯覆盖缺失 Facade/配置，绿灯后目标用例 15 tests pass；提交 `83c268c`。
- Task 4：完成 `POST /api/datasets/{id}/retrieve` 命中测试端点；红灯覆盖端点缺失，绿灯后目标用例 18 tests pass；提交 `d4ce084`。
- Task 5：完成应用绑定知识库、校验、全量替换与运行时 `datasetIds`；红灯覆盖绑定缺失，绿灯后目标用例 37 tests pass，后端全量 421 tests pass；提交 `48e1e06`。
- Task 6：完成会话按绑定库检索并拼系统提示词，检索失败降级继续答；红灯覆盖未注入知识上下文，绿灯后目标用例 21 tests pass，后端全量 426 tests pass；提交 `538344b`。
- Task 7：完成前端命中测试 API 与应用类型 `datasetIds`；红灯覆盖 API/类型缺失，绿灯后前端全量 31 files / 222 tests pass，`pnpm build` pass；提交 `8946a14`。
- Task 8：完成应用弹窗知识库多选与已删库禁用回显；红灯覆盖绑定控件缺失，绿灯后 `AppList.spec.ts` 18 tests pass；提交 `3e20080`。
- Task 9：完成知识库详情页命中测试弹窗、分数/文档名/空态；红灯覆盖弹窗缺失，绿灯后前端全量 31 files / 228 tests pass，`pnpm build` pass；提交 `a827e26`。
- Task 10：完成架构文档入档，核对 `docs/architecture/data-model.md` 已含 `app_dataset_rel` 无需修改；最终 `mvn verify -Dapi.version=1.54` 426 tests pass，`pnpm test` 31 files / 228 tests pass，`pnpm build` pass。

偏差清单：

1. Task 1：计划将 `@Transactional` 直接放在 `PgIntegrationTest`；因 `LayerRulesTest` 禁止 `..service..` 外使用 `@Transactional`，改为 `com.hify.support.service.TransactionalPgIntegrationTest` 承载事务注解，`PgIntegrationTest` 继承它，保留测试回滚语义。
2. Task 1/全局验证：本机 Docker Desktop 29.5.3 需通过 `DOCKER_HOST=unix:///var/run/docker.sock` 与 `-Dapi.version=1.54` 运行 Testcontainers；沙箱内直接访问 Docker socket 会 permission denied，因此相关 Maven/Docker 命令使用提权执行。
3. Task 2：计划测试包路径为 `knowledge.mapper`；因 `LayerRulesTest` 禁止 mapper 依赖出现在 `..service..` 外，`KbChunkRetrievalTest` 放在 `com.hify.knowledge.service`，测试仍直测 Mapper SQL 行为。
4. Task 2：移动测试包后执行过一次 `mvn clean test` 清理旧 `target/test-classes` 中的陈旧类，避免架构规则扫描到已删除包路径。
5. Task 3/4/5：计划断言示例使用 `BizException#getError().code()`；仓库现有 `BizException` 暴露的是 `errorCode()`，测试按现有 API 对齐。
6. Task 7：补齐应用前端 fixture 时按现有 `AppForm` 类型一并补了 `modelId: null`，避免类型检查失败。
7. Task 8：计划测试选择器写作 `[data-test="form-name"] input`；当前 Element Plus 测试写法已在文件中使用 `[data-test="form-name"].setValue(...)`，按既有 helper 风格对齐。
8. Task 9：计划测试选择器假定 dialog 在 `document.body` 且 `data-test` 包在外层；当前测试环境 teleport stub 在 wrapper 内，且 Element Plus 输入组件把 `data-test` 落在 input 上，helper 改为从 `wrapper.element` 查找并兼容两种 selector。
9. Task 10：`database-standards.md` 新句中将 `kb_chunk` 与 `PgVectorStore` 加了反引号，语义与计划代码块一致，仅做文档标识格式对齐。
