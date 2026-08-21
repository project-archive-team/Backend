package com.projectarchive.backend.collect;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("syncExecutor")
    public Executor syncExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        // AI 서버의 색인은 메모리 사용량이 크므로 프로젝트를 병렬 처리하지 않는다.
        // 추가 요청은 큐에서 대기하고 현재 프로젝트가 끝난 뒤 순차 실행한다.
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("sync-");
        executor.initialize();
        return executor;
    }
}
