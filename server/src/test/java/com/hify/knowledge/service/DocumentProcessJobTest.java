package com.hify.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hify.common.exception.BizException;
import com.hify.knowledge.entity.KbChunk;
import com.hify.knowledge.entity.KbDocument;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.knowledge.mapper.KbDocumentMapper;
import com.hify.provider.api.ProviderFacade;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentProcessJobTest {

    private KbDocumentMapper documentMapper;
    private KbChunkMapper chunkMapper;
    private DocumentProcessStore store;
    private ProviderFacade providerFacade;
    private EmbeddingModel embeddingModel;
    private ReembedGate gate;
    private DocumentProcessJob job;

    @BeforeEach
    void setUp() {
        initTableInfo(KbDocument.class);
        initTableInfo(KbChunk.class);
        documentMapper = mock(KbDocumentMapper.class);
        chunkMapper = mock(KbChunkMapper.class);
        store = mock(DocumentProcessStore.class);
        providerFacade = mock(ProviderFacade.class);
        embeddingModel = mock(EmbeddingModel.class);
        gate = mock(ReembedGate.class);
        job = new DocumentProcessJob(documentMapper, chunkMapper, store, providerFacade, gate, 2);
    }

    private void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), entityClass);
        }
    }

    private KbDocument processingDoc() {
        KbDocument doc = new KbDocument();
        doc.setId(20L);
        doc.setDatasetId(10L);
        doc.setName("faq.txt");
        doc.setStatus("processing");
        doc.setChunkSize(100);
        doc.setChunkOverlap(10);
        doc.setContent("x".repeat(250).getBytes(StandardCharsets.UTF_8));
        return doc;
    }

    private KbChunk chunk(long id) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setContent("段" + id);
        return c;
    }

    @Test
    void 上传事件_claim失败_直接返回不处理() {
        when(documentMapper.claimStatus(20L, "pending")).thenReturn(0);
        job.onDocumentUploaded(new DocumentUploadedEvent(20L));
        verify(documentMapper, never()).selectById(anyLong());
    }

    @Test
    void runOnce_分段不存在_提取分段后嵌入并ready() {
        when(documentMapper.selectById(20L)).thenReturn(processingDoc());
        when(chunkMapper.selectCount(any())).thenReturn(0L);
        when(chunkMapper.selectUnembedded(20L)).thenReturn(List.of(chunk(1), chunk(2), chunk(3)));
        when(providerFacade.getEmbeddingModel()).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyList())).thenAnswer(inv ->
                ((List<String>) inv.getArgument(0)).stream().map(s -> new float[1024]).toList());

        job.runOnce(20L);

        verify(store).saveChunks(any(KbDocument.class), eq(List.of(
                "x".repeat(100), "x".repeat(100), "x".repeat(70))));
        verify(embeddingModel, times(2)).embed(anyList());
        verify(store, times(2)).writeEmbeddings(anyList(), anyList());
        verify(documentMapper).markReady(20L);
        verify(documentMapper, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void runOnce_分段已存在_跳过提取只补空向量段() {
        when(documentMapper.selectById(20L)).thenReturn(processingDoc());
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(chunkMapper.selectUnembedded(20L)).thenReturn(List.of(chunk(3)));
        when(providerFacade.getEmbeddingModel()).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[1024]));

        job.runOnce(20L);

        verify(store, never()).saveChunks(any(), anyList());
        verify(embeddingModel, times(1)).embed(anyList());
        verify(documentMapper).markReady(20L);
    }

    @Test
    void runOnce_全部已嵌_不调模型直接ready() {
        when(documentMapper.selectById(20L)).thenReturn(processingDoc());
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(chunkMapper.selectUnembedded(20L)).thenReturn(List.of());

        job.runOnce(20L);

        verify(providerFacade, never()).getEmbeddingModel();
        verify(documentMapper).markReady(20L);
    }

    @Test
    void runOnce_内容全空白_failed且原因是15001文案() {
        KbDocument blank = processingDoc();
        blank.setContent("   \n\t ".getBytes(StandardCharsets.UTF_8));
        when(documentMapper.selectById(20L)).thenReturn(blank);
        when(chunkMapper.selectCount(any())).thenReturn(0L);

        job.runOnce(20L);

        verify(documentMapper).markFailed(eq(20L), eq("文档内容为空或无法解析"));
        verify(documentMapper, never()).markReady(anyLong());
    }

    @Test
    void runOnce_未配置embedding模型_failed且原因可读() {
        when(documentMapper.selectById(20L)).thenReturn(processingDoc());
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(chunkMapper.selectUnembedded(20L)).thenReturn(List.of(chunk(1)));
        when(providerFacade.getEmbeddingModel()).thenThrow(new BizException(
                com.hify.provider.constant.ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED));

        job.runOnce(20L);

        verify(documentMapper).markFailed(eq(20L),
                eq("系统未配置 embedding 模型，请联系管理员在系统设置中配置"));
    }

    @Test
    void runOnce_批中途失败_已写批保留且failed() {
        when(documentMapper.selectById(20L)).thenReturn(processingDoc());
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(chunkMapper.selectUnembedded(20L)).thenReturn(List.of(chunk(1), chunk(2), chunk(3)));
        when(providerFacade.getEmbeddingModel()).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyList()))
                .thenReturn(List.of(new float[1024], new float[1024]))
                .thenThrow(new RuntimeException("boom"));

        job.runOnce(20L);

        verify(store, times(1)).writeEmbeddings(anyList(), anyList());
        verify(documentMapper).markFailed(eq(20L), eq("处理失败，请重试"));
    }

    @Test
    void runOnce_文档已删_静默退出() {
        when(documentMapper.selectById(99L)).thenReturn(null);
        job.runOnce(99L);
        verify(documentMapper, never()).markReady(anyLong());
        verify(documentMapper, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void reembedAll_清空向量后逐文档处理_单个失败不中断_收尾释放闸() {
        when(documentMapper.selectReembedTargetIds()).thenReturn(List.of(21L, 22L));
        when(documentMapper.claimForReembed(21L)).thenReturn(1);
        when(documentMapper.claimForReembed(22L)).thenReturn(1);
        when(documentMapper.selectById(21L)).thenThrow(new RuntimeException("boom"));
        KbDocument ok = processingDoc();
        ok.setId(22L);
        when(documentMapper.selectById(22L)).thenReturn(ok);
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(chunkMapper.selectUnembedded(22L)).thenReturn(List.of());

        job.reembedAll();

        verify(chunkMapper).clearAllEmbeddings();
        verify(documentMapper).markFailed(eq(21L), anyString());
        verify(documentMapper).markReady(22L);
        verify(gate).finish();
    }

    @Test
    void reembedAll_claim为0的文档跳过() {
        when(documentMapper.selectReembedTargetIds()).thenReturn(List.of(21L));
        when(documentMapper.claimForReembed(21L)).thenReturn(0);

        job.reembedAll();

        verify(documentMapper, never()).selectById(21L);
        verify(gate).finish();
    }
}
