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
        assertEquals(10005, e.errorCode().code());
    }
}
