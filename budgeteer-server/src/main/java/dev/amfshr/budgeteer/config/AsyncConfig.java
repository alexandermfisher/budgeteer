package dev.amfshr.budgeteer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    private final TransactionSyncProperties props;

    public AsyncConfig(TransactionSyncProperties props) {
        this.props = props;
    }

    @Bean(name = "backfillTaskExecutor")
    public Executor backfillTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.backfillCorePoolSize());
        executor.setMaxPoolSize(props.backfillMaxPoolSize());
        executor.setQueueCapacity(props.backfillQueueCapacity());
        executor.setThreadNamePrefix("backfill-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Uncaught async exception in {}: {}", method.getName(), ex.getMessage(), ex);
    }
}
