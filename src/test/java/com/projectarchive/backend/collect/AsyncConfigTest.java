package com.projectarchive.backend.collect;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncConfigTest {

    @Test
    void syncExecutorProcessesOnlyOneProjectAtATime() {
        ThreadPoolTaskExecutor executor =
                (ThreadPoolTaskExecutor) new AsyncConfig().syncExecutor();

        try {
            assertEquals(1, executor.getCorePoolSize());
            assertEquals(1, executor.getMaxPoolSize());
        } finally {
            executor.shutdown();
        }
    }
}
