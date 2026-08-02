package com.genersoft.iot.vmp.conf.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Runs Redis Stream consumers outside the application's scheduled executor. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStreamConsumer {

    private final RedisStreamMessageService messageService;
    private final RedisStreamConfig config;
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();

    public void start(String stream, String group, String consumer,
                      Function<MapRecord<String, String, String>, Boolean> handler) {
        if (!config.isEnabled()) {
            log.info("[Redis Stream] 已禁用消费：stream={}, group={}", stream, group);
            return;
        }
        String key = stream + "|" + group + "|" + consumer;
        workers.computeIfAbsent(key, ignored -> {
            Worker worker = new Worker(stream, group, consumer, handler);
            worker.thread = Thread.startVirtualThread(worker);
            return worker;
        });
    }

    public void stop() {
        workers.values().forEach(Worker::stop);
        workers.clear();
    }

    public void stop(String stream, String group, String consumer) {
        Worker worker = workers.remove(stream + "|" + group + "|" + consumer);
        if (worker != null) {
            worker.stop();
        }
    }

    private final class Worker implements Runnable {
        private final String stream;
        private final String group;
        private final String consumer;
        private final Function<MapRecord<String, String, String>, Boolean> handler;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile Thread thread;

        private Worker(String stream, String group, String consumer,
                       Function<MapRecord<String, String, String>, Boolean> handler) {
            this.stream = stream;
            this.group = group;
            this.consumer = consumer;
            this.handler = handler;
        }

        @Override
        public void run() {
            Duration block = Duration.ofMillis(Math.max(100L, config.getBlockMillis()));
            Duration idle = Duration.ofSeconds(Math.max(1L, config.getClaimIdleSeconds()));
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    messageService.ensureGroup(stream, group);
                    process(messageService.reclaim(stream, group, consumer, idle,
                            Math.max(1, config.getClaimBatchSize())));
                    process(messageService.read(stream, group, consumer,
                            Math.max(1, config.getBatchSize()), block));
                } catch (Exception e) {
                    if (running.get()) {
                        log.warn("[Redis Stream] 消费异常，稍后重试：stream={}, group={}, consumer={}, error={}",
                                stream, group, consumer, e.getMessage());
                        try {
                            Thread.sleep(Math.min(5000L, block.toMillis()));
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        private void process(List<MapRecord<String, String, String>> records) {
            if (records == null) {
                return;
            }
            for (MapRecord<String, String, String> record : records) {
                if (!running.get()) {
                    return;
                }
                try {
                    Boolean handled = handler.apply(record);
                    if (Boolean.TRUE.equals(handled)) {
                        messageService.ack(stream, group, record.getId());
                    }
                } catch (Exception e) {
                    log.warn("[Redis Stream] 消息处理失败，保留Pending：stream={}, id={}, error={}",
                            stream, record.getId(), e.getMessage());
                }
            }
        }

        private void stop() {
            running.set(false);
            Thread current = thread;
            if (current != null) {
                current.interrupt();
                try {
                    current.join(Math.max(1000L, config.getBlockMillis() + 1000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
