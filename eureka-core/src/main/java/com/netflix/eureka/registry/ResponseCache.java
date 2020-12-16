package com.netflix.eureka.registry;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author David Liu
 */
public interface ResponseCache {

    /**
     * 缓存失效
     * @param appName
     * @param vipAddress
     * @param secureVipAddress
     */
    void invalidate(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress);

    /**
     * 获取增量的版本号
     * @return
     */
    AtomicLong getVersionDelta();

    /**
     * 获取带有区域的版本Delta
     *
     * @return
     */
    AtomicLong getVersionDeltaWithRegions();

    /**
     * 获取有关应用程序的缓存信息。
     *
     * Get the cached information about applications.
     *
     * <p>
     * If the cached information is not available it is generated on the first
     * request. After the first request, the information is then updated
     * periodically by a background thread.
     * </p>
     *
     * @param key the key for which the cached information needs to be obtained.
     * @return payload which contains information about the applications.
     */
     String get(Key key);

    /**
     * gzip 的数据格式
     * Get the compressed information about the applications.
     *
     * @param key the key for which the compressed cached information needs to be obtained.
     * @return compressed payload which contains information about the applications.
     */
    byte[] getGZIP(Key key);

    /**
     * 通过停止内部线程并取消注册伺服监视器来关闭此缓存。
     *
     * Performs a shutdown of this cache by stopping internal threads and unregistering
     * Servo monitors.
     */
    void stop();
}
