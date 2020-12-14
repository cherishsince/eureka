/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.netflix.eureka.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实用程序类，用于获取过去X毫秒内的计数。
 * <p>
 * Utility class for getting a count in last X milliseconds.
 *
 * @author Karthik Ranganathan,Greg Kim
 */
public class MeasuredRate {
    private static final Logger logger = LoggerFactory.getLogger(MeasuredRate.class);
    /**
     * 上一次统计的次数
     */
    private final AtomicLong lastBucket = new AtomicLong(0);
    /**
     * 当前统计的次数
     */
    private final AtomicLong currentBucket = new AtomicLong(0);

    private final long sampleInterval;
    /**
     * 定时器
     */
    private final Timer timer;
    /**
     * 是否激活(start 的时候激活)
     */
    private volatile boolean isActive;

    /**
     * @param sampleInterval in milliseconds
     */
    public MeasuredRate(long sampleInterval) {
        this.sampleInterval = sampleInterval;
        this.timer = new Timer("Eureka-MeasureRateTimer", true);
        this.isActive = false;
    }

    public synchronized void start() {
        if (!isActive) {
            timer.schedule(new TimerTask() {

                @Override
                public void run() {
                    try {
                        // 将当前存储桶清零，并保存到 lastBucket
                        // Zero out the current bucket.
                        lastBucket.set(currentBucket.getAndSet(0));
                    } catch (Throwable e) {
                        logger.error("Cannot reset the Measured Rate", e);
                    }
                }
            }, sampleInterval, sampleInterval);
            isActive = true;
        }
    }

    public synchronized void stop() {
        if (isActive) {
            timer.cancel();
            isActive = false;
        }
    }

    /**
     * 获取上一次统计的 count
     * <p>
     * Returns the count in the last sample interval.
     */
    public long getCount() {
        return lastBucket.get();
    }

    /**
     * 次数 +1
     * <p>
     * Increments the count in the current sample interval.
     */
    public void increment() {
        currentBucket.incrementAndGet();
    }
}
