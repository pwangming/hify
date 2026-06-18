package com.hify.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.common.page.PageResult;
import com.hify.demo.dto.CreateDemoItemRequest;
import com.hify.demo.dto.DemoItemResponse;
import com.hify.demo.dto.UpdateDemoItemRequest;
import com.hify.demo.entity.DemoItem;
import com.hify.demo.mapper.DemoItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * DemoItem 业务逻辑。具体类 + {@code @Service}（不写 service 接口、不拆 impl——code-organization.md 第 2 节）。
 *
 * <p>分层职责：本层是业务唯一所在地，注入本模块 Mapper；{@code @Transactional} 只允许出现在这一层；
 * Entity ↔ DTO 转换也在这里完成（{@link #toResponse}）。
 */
@Service
public class DemoItemService {

    private final DemoItemMapper demoItemMapper;

    public DemoItemService(DemoItemMapper demoItemMapper) {
        this.demoItemMapper = demoItemMapper;
    }

    @Transactional
    public DemoItemResponse create(CreateDemoItemRequest request) {
        DemoItem entity = new DemoItem();
        entity.setName(request.name());
        entity.setStatus(request.status());
        // insert 后 id 回填到 entity；create_time/update_time/deleted 由 MetaObjectHandler 自动填充
        demoItemMapper.insert(entity);
        return toResponse(entity);
    }

    @Transactional
    public DemoItemResponse update(Long id, UpdateDemoItemRequest request) {
        DemoItem entity = demoItemMapper.selectById(id);
        if (entity == null) {
            throw new BizException(CommonError.NOT_FOUND, "DemoItem 不存在");
        }
        entity.setName(request.name());
        entity.setStatus(request.status());
        demoItemMapper.updateById(entity); // update_time 自动刷新
        return toResponse(entity);
    }

    public DemoItemResponse get(Long id) {
        DemoItem entity = demoItemMapper.selectById(id);
        if (entity == null) {
            throw new BizException(CommonError.NOT_FOUND, "DemoItem 不存在");
        }
        return toResponse(entity);
    }

    /** 页码分页。@TableLogic 会自动给查询加 {@code where deleted = false}，软删的行不会出现在列表里。 */
    public PageResult<DemoItemResponse> page(int page, int size) {
        Page<DemoItem> result = demoItemMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<DemoItem>().orderByDesc(DemoItem::getId)); // 以 id 结尾保证稳定排序
        List<DemoItemResponse> list = result.getRecords().stream().map(this::toResponse).toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }

    /** 逻辑删除：@TableLogic 把 delete 变成 {@code update set deleted=true}；删不存在的也返回成功（幂等）。 */
    @Transactional
    public void delete(Long id) {
        demoItemMapper.deleteById(id);
    }

    private DemoItemResponse toResponse(DemoItem entity) {
        return new DemoItemResponse(
                entity.getId(),
                entity.getName(),
                entity.getStatus(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }
}
