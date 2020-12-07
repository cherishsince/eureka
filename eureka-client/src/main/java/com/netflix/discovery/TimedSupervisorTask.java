package com.netflix.discovery;

import java.util.TimerTask;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.LongGauge;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A supervisor task that schedules subtasks while enforce a timeout.
 * Wrapped subtasks must be thread safe.
 *
 * @author David Qiang Liu
 */
public class TimedSupervisorTask extends TimerTask {
    private static final Logger logger = LoggerFactory.getLogger(TimedSupervisorTask.class);

    private final Counter successCounter;
    private final Counter timeoutCounter;
    private final Counter rejectedCounter;
    private final Counter throwableCounter;
    private final LongGauge threadPoolLevelGauge;

    private final String name;
    private final ScheduledExecutorService scheduler;
    private final ThreadPoolExecutor executor;
    private final long timeoutMillis;
    private final Runnable task;

    private final AtomicLong delay;
    private final long maxDelay;

    public TimedSupervisorTask(String name, ScheduledExecutorService scheduler, ThreadPoolExecutor executor,
                               int timeout, TimeUnit timeUnit, int expBackOffBound, Runnable task) {
        this.name = name;
        // 调度执行器
        this.scheduler = scheduler;
        // 执行器
        this.executor = executor;
        this.timeoutMillis = timeUnit.toMillis(timeout);
        this.task = task;
        this.delay = new AtomicLong(timeoutMillis);
        // 最大延时时间
        this.maxDelay = timeoutMillis * expBackOffBound;

        // Initialize the counters and register.
        successCounter = Monitors.newCounter("success");
        timeoutCounter = Monitors.newCounter("timeouts");
        rejectedCounter = Monitors.newCounter("rejectedExecutions");
        throwableCounter = Monitors.newCounter("throwables");
        threadPoolLevelGauge = new LongGauge(MonitorConfig.builder("threadPoolUsed").build());
        Monitors.registerObject(name, this);
    }

    @Override
    public void run() {
        // Future 任务
        Future<?> future = null;
        try {
            future = executor.submit(task);
            // 获取线程池 激活的数量 设置到threadPoolLevelGauge
            threadPoolLevelGauge.set((long) executor.getActiveCount());
            // 获取 future 返回值信息，设置一个超时时间
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);  // block until done or timeout
            // 设置延时时间
            // tip: TimedSupervisorTask 构造方法中也初始化一次，这里每次 run 的时候重新设置，catch 中会重新计算本次请求的时间
            delay.set(timeoutMillis);
            // 获取线程池 激活的数量 设置到threadPoolLevelGauge
            threadPoolLevelGauge.set((long) executor.getActiveCount());
            // 这就是一个 AtomicLong 计数器，每次都 +1
            successCounter.increment();
        } catch (TimeoutException e) {
            logger.warn("task supervisor timed out", e);
            // 超时记录
            timeoutCounter.increment();
            // tip: 超时时间和最大超时时间，取最小，所以在设置的时候需要注意
            long currentDelay = delay.get();
            long newDelay = Math.min(maxDelay, currentDelay * 2);
            // 重新设置延时时间
            delay.compareAndSet(currentDelay, newDelay);

            // tip：超时时间10秒，最大30秒
            // tip: 超时后进入，newDelay 是 20秒，delay 就是 20，下次请求超时时间就会变大（用于处理服务器网络波动情况）
            // tip: delay 是只增不减的，只要 timeout 一次，那么时间就是 newDelay
        } catch (RejectedExecutionException e) {
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, reject the task", e);
            } else {
                logger.warn("task supervisor rejected the task", e);
            }
            // 请求拒绝，每次都 +1
            rejectedCounter.increment();
        } catch (Throwable e) {
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, can't accept the task");
            } else {
                logger.warn("task supervisor threw an exception", e);
            }
            // 未知的异常，每次都 +1
            throwableCounter.increment();
        } finally {
            // 关闭 future
            if (future != null) {
                future.cancel(true);
            }
            // tip：这里有点意思，这是一个死循环(scheduler 线程池没有关闭的情况下)
            // tip: scheduler 是一个外部传入的 ScheduledExecutorService，外面没有关闭 scheduler 那么就会一直 run.
            if (!scheduler.isShutdown()) {
                scheduler.schedule(this, delay.get(), TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public boolean cancel() {
        // 取消注册
        Monitors.unregisterObject(name, this);
        return super.cancel();
    }
}