package com.hify.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
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
        initTableInfo(KbDocument.class);
        initTableInfo(KbChunk.class);
        datasetMapper = mock(DatasetMapper.class);
        documentMapper = mock(KbDocumentMapper.class);
        chunkMapper = mock(KbChunkMapper.class);
        // chunkSize=100 / overlap=10：测试用小参数，切分结果好断言
        service = new DocumentService(datasetMapper, documentMapper, chunkMapper, 100, 10);
        dbMock = mockStatic(Db.class);
        dbMock.when(() -> Db.saveBatch(anyList(), anyInt())).thenReturn(true);
    }

    private void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), entityClass);
        }
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
