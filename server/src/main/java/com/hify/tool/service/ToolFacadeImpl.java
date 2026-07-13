package com.hify.tool.service;

import com.hify.tool.api.ToolFacade;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;

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
}
