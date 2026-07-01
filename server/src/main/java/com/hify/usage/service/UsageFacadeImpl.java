package com.hify.usage.service;

import com.hify.usage.api.UsageFacade;
import org.springframework.stereotype.Service;

/** {@link UsageFacade} 实现：委托 {@link UsageService}。 */
@Service
public class UsageFacadeImpl implements UsageFacade {

    private final UsageService usageService;

    public UsageFacadeImpl(UsageService usageService) {
        this.usageService = usageService;
    }

    @Override
    public void checkQuota(Long userId, Long appId) {
        usageService.checkQuota(userId, appId);
    }
}
