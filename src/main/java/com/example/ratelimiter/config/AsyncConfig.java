package com.example.ratelimiter.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    /**
     * Event recording must never slow down or fail a request. The queue is bounded and, when it is
     * full, extra events are dropped (the Micrometer counters still count them).
     */
    @Bean(name = "rlEvents")
    public Executor rlEvents() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(10_000);
        ex.setThreadNamePrefix("rl-events-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        ex.initialize();
        return ex;
    }
}
