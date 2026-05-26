package com.portfolio.clipcurator.config;

import com.portfolio.clipcurator.processing.VideoProcessingWorker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Objects;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class ProcessingConfig {

    public static final String VIDEO_PROCESSING_TOPIC = "video-processing-queue";

    @Bean
    public ChannelTopic videoProcessingTopic() {
        return new ChannelTopic(VIDEO_PROCESSING_TOPIC);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            VideoProcessingWorker videoProcessingWorker,
            ChannelTopic videoProcessingTopic
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(Objects.requireNonNull(connectionFactory));
        container.addMessageListener(
            Objects.requireNonNull(videoProcessingWorker),
            Objects.requireNonNull(videoProcessingTopic)
        );
        return container;
    }

    @Bean(name = "videoWorkerExecutor")
    public Executor videoWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("video-worker-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
