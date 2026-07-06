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
