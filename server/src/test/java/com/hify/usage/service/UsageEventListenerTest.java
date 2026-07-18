package com.hify.usage.service;

import com.hify.common.event.TokenUsedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UsageEventListenerTest {

    @Test
    void 收到TokenUsedEvent_委托recordUsage() {
        UsageService usageService = mock(UsageService.class);
        UsageEventListener listener = new UsageEventListener(usageService);
        TokenUsedEvent event = TokenUsedEvent.success(
                7L, 88L, 5L, 300, 180, TokenUsedEvent.SOURCE_CONVERSATION, 100L);

        listener.onTokenUsed(event);

        verify(usageService, times(1)).recordUsage(event);
    }
}
