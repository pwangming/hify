package com.hify.tool.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.tool.api.ToolFacade;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ToolFacadeImpl implements ToolFacade {

    private final ToolRegistry registry;

    public ToolFacadeImpl(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<ToolCallback> getBuiltinToolCallbacks() {
        return registry.getBuiltinToolCallbacks();
    }

    @Override
    public List<ToolCallback> getToolCallbacks(Collection<Long> toolIds) {
        return registry.getToolCallbacks(toolIds);
    }

    @Override
    public void validateToolIds(Collection<Long> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return;
        }
        Set<Long> distinct = new HashSet<>(toolIds);
        if (!registry.filterEnabledIds(distinct).containsAll(distinct)) {
            throw new BizException(CommonError.PARAM_INVALID, "存在不可用的工具，请重新选择");
        }
    }
}
