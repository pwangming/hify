package com.hify.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.dto.CreateDatasetRequest;
import com.hify.knowledge.dto.DatasetResponse;
import com.hify.knowledge.dto.UpdateDatasetRequest;
import com.hify.knowledge.entity.Dataset;
import com.hify.knowledge.mapper.DatasetMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 知识库业务逻辑。团队共享权限判定在本层（assertCanModify）。
 * 当前用户由 controller 经 CurrentUserHolder 传入，本层不直接读安全上下文（便于单测）。
 * 重名不做插入前预查：靠部分唯一索引 + DuplicateKeyException → CONFLICT（无并发窗口，照 AppService）。
 */
@Service
public class DatasetService {

    private final DatasetMapper datasetMapper;

    public DatasetService(DatasetMapper datasetMapper) {
        this.datasetMapper = datasetMapper;
    }

    @Transactional
    public DatasetResponse create(CreateDatasetRequest req, CurrentUser current) {
        Dataset entity = new Dataset();
        entity.setName(req.name());
        entity.setDescription(req.description());
        entity.setOwnerId(current.userId());
        try {
            datasetMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "知识库名已存在", e);
        }
        return toResponse(entity);
    }

    public DatasetResponse get(Long id) {
        return toResponse(loadOrThrow(id));
    }

    public PageResult<DatasetResponse> page(String keyword, int page, int size) {
        if (page < 1 || size < 1 || (long) page * size > 10_000) {
            throw new BizException(CommonError.PARAM_INVALID, "分页参数非法或过深，请用筛选条件缩小范围");
        }
        Page<Dataset> result = datasetMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<Dataset>()
                        .like(StringUtils.hasText(keyword), Dataset::getName, keyword)
                        .orderByDesc(Dataset::getId)); // id 倒序=按创建先后稳定排序；@TableLogic 自动加 deleted=false
        return PageResult.of(result.getRecords().stream().map(this::toResponse).toList(),
                result.getTotal(), page, size);
    }

    @Transactional
    public DatasetResponse update(Long id, UpdateDatasetRequest req, CurrentUser current) {
        Dataset dataset = loadOrThrow(id);
        assertCanModify(dataset, current);
        dataset.setName(req.name());
        dataset.setDescription(req.description());
        try {
            datasetMapper.updateById(dataset);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "知识库名已存在", e);
        }
        return toResponse(dataset);
    }

    @Transactional
    public void delete(Long id, CurrentUser current) {
        Dataset dataset = datasetMapper.selectById(id);
        if (dataset == null) {
            return; // 幂等：删不存在的也算成功（api-standards §2.2）
        }
        assertCanModify(dataset, current);
        datasetMapper.deleteById(id);
    }

    private Dataset loadOrThrow(Long id) {
        Dataset dataset = datasetMapper.selectById(id);
        if (dataset == null) {
            throw new BizException(CommonError.NOT_FOUND, "知识库不存在");
        }
        return dataset;
    }

    /** 团队共享制：仅 owner 或 Admin 可改/删（api-standards 第 6 节），否则 FORBIDDEN。 */
    private void assertCanModify(Dataset dataset, CurrentUser current) {
        if (!current.isAdmin() && !current.userId().equals(dataset.getOwnerId())) {
            throw new BizException(CommonError.FORBIDDEN, "仅创建者或管理员可操作该知识库");
        }
    }

    private DatasetResponse toResponse(Dataset e) {
        return new DatasetResponse(e.getId(), e.getName(), e.getDescription(),
                e.getOwnerId(), e.getCreateTime(), e.getUpdateTime());
    }
}
