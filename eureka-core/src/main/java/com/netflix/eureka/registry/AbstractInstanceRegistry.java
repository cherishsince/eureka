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

package com.netflix.eureka.registry;

import com.google.common.cache.CacheBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.Pair;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.registry.rule.InstanceStatusOverrideRule;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.MeasuredRate;
import com.netflix.servo.annotations.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.netflix.eureka.util.EurekaMonitors.*;

/**
 * Handles all registry requests from eureka clients.
 *
 * <p>
 * Primary operations that are performed are the
 * <em>Registers</em>, <em>Renewals</em>, <em>Cancels</em>, <em>Expirations</em>, and <em>Status Changes</em>. The
 * registry also stores only the delta operations
 * </p>
 *
 * @author Karthik Ranganathan
 */
public abstract class AbstractInstanceRegistry implements InstanceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AbstractInstanceRegistry.class);

    private static final String[] EMPTY_STR_ARRAY = new String[0];
    // 注册的实例
    private final ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>> registry
            = new ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>>();
    // 区域名称VS远程注册表(一般是另外的集群，或者是云服务商的集群)
    protected Map<String, RemoteRegionRegistry> regionNameVSRemoteRegistry = new HashMap<String, RemoteRegionRegistry>();
    // 保存的是 服务的状态
    protected final ConcurrentMap<String, InstanceStatus> overriddenInstanceStatusMap = CacheBuilder
            .newBuilder().initialCapacity(500)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .<String, InstanceStatus>build().asMap();
    // CircularQueues here for debugging/statistics purposes only
    // 最近注册队列
    private final CircularQueue<Pair<Long, String>> recentRegisteredQueue;
    // 最近取消的队列
    private final CircularQueue<Pair<Long, String>> recentCanceledQueue;
    // 最近变更的队列
    private ConcurrentLinkedQueue<RecentlyChangedItem> recentlyChangedQueue = new ConcurrentLinkedQueue<RecentlyChangedItem>();

    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock read = readWriteLock.readLock();
    private final Lock write = readWriteLock.writeLock();
    protected final Object lock = new Object();

    private Timer deltaRetentionTimer = new Timer("Eureka-DeltaRetentionTimer", true);
    private Timer evictionTimer = new Timer("Eureka-EvictionTimer", true);
    // 续租每分钟次数
    private final MeasuredRate renewsLastMin;

    private final AtomicReference<EvictionTask> evictionTaskRef = new AtomicReference<EvictionTask>();

    protected String[] allKnownRemoteRegions = EMPTY_STR_ARRAY;
    // eureka server 阀值信息
    protected volatile int numberOfRenewsPerMinThreshold;
    // 客户端发送续约，预期的一个数量(就是客户端数量)
    protected volatile int expectedNumberOfClientsSendingRenews;

    protected final EurekaServerConfig serverConfig;
    protected final EurekaClientConfig clientConfig;
    protected final ServerCodecs serverCodecs;
    protected volatile ResponseCache responseCache;

    /**
     * Create a new, empty instance registry.
     */
    protected AbstractInstanceRegistry(EurekaServerConfig serverConfig, EurekaClientConfig clientConfig, ServerCodecs serverCodecs) {
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.serverCodecs = serverCodecs;
        this.recentCanceledQueue = new CircularQueue<Pair<Long, String>>(1000);
        this.recentRegisteredQueue = new CircularQueue<Pair<Long, String>>(1000);

        this.renewsLastMin = new MeasuredRate(1000 * 60 * 1);

        this.deltaRetentionTimer.schedule(getDeltaRetentionTask(),
                serverConfig.getDeltaRetentionTimerIntervalInMs(),
                serverConfig.getDeltaRetentionTimerIntervalInMs());
    }

    @Override
    public synchronized void initializedResponseCache() {
        if (responseCache == null) {
            responseCache = new ResponseCacheImpl(serverConfig, serverCodecs, this);
        }
    }

    protected void initRemoteRegionRegistry() throws MalformedURLException {
        Map<String, String> remoteRegionUrlsWithName = serverConfig.getRemoteRegionUrlsWithName();
        if (!remoteRegionUrlsWithName.isEmpty()) {
            allKnownRemoteRegions = new String[remoteRegionUrlsWithName.size()];
            int remoteRegionArrayIndex = 0;
            for (Map.Entry<String, String> remoteRegionUrlWithName : remoteRegionUrlsWithName.entrySet()) {
                RemoteRegionRegistry remoteRegionRegistry = new RemoteRegionRegistry(
                        serverConfig,
                        clientConfig,
                        serverCodecs,
                        remoteRegionUrlWithName.getKey(),
                        new URL(remoteRegionUrlWithName.getValue()));
                regionNameVSRemoteRegistry.put(remoteRegionUrlWithName.getKey(), remoteRegionRegistry);
                allKnownRemoteRegions[remoteRegionArrayIndex++] = remoteRegionUrlWithName.getKey();
            }
        }
        logger.info("Finished initializing remote region registries. All known remote regions: {}",
                (Object) allKnownRemoteRegions);
    }

    @Override
    public ResponseCache getResponseCache() {
        return responseCache;
    }

    public long getLocalRegistrySize() {
        long total = 0;
        for (Map<String, Lease<InstanceInfo>> entry : registry.values()) {
            total += entry.size();
        }
        return total;
    }

    /**
     * 完全清除注册表。
     * <p>
     * Completely clear the registry.
     */
    @Override
    public void clearRegistry() {
        overriddenInstanceStatusMap.clear();
        recentCanceledQueue.clear();
        recentRegisteredQueue.clear();
        recentlyChangedQueue.clear();
        registry.clear();
    }

    // for server info use
    @Override
    public Map<String, InstanceStatus> overriddenInstanceStatusesSnapshot() {
        return new HashMap<>(overriddenInstanceStatusMap);
    }

    /**
     * 注册具有给定持续时间的新实例。
     * Registers a new instance with a given duration.
     *
     * @see com.netflix.eureka.lease.LeaseManager#register(java.lang.Object, int, boolean)
     */
    public void register(InstanceInfo registrant, int leaseDuration, boolean isReplication) {
        // 读锁(ReentrantReadWriteLock)
        read.lock();
        try {
            // 根据 applicationName 获取注册的实例信息
            Map<String, Lease<InstanceInfo>> gMap = registry.get(registrant.getAppName());
            // 计数器 +1
            REGISTER.increment(isReplication);
            // 如果 Application 应用程序为 null，就代表还没注册过
            if (gMap == null) {
                // tip: 没有就创建一个，然后给 gMap
                final ConcurrentHashMap<String, Lease<InstanceInfo>> gNewMap = new ConcurrentHashMap<String, Lease<InstanceInfo>>();
                gMap = registry.putIfAbsent(registrant.getAppName(), gNewMap);
                if (gMap == null) {
                    gMap = gNewMap;
                }
            }

            // id是我们在eureka那个很长的，如：localhost:demo-provider:18080
            // tip: 新注册的 gMap 是没有的
            Lease<InstanceInfo> existingLease = gMap.get(registrant.getId());

            // 如果已经有租约，则保留最后的脏时间戳而不覆盖它
            // Retain the last dirty timestamp without overwriting it, if there is already a lease
            // tip: 这里是属于重复注册，保留最后一个注册的 InstanceInfo
            if (existingLease != null && (existingLease.getHolder() != null)) {
                Long existingLastDirtyTimestamp = existingLease.getHolder().getLastDirtyTimestamp();
                Long registrationLastDirtyTimestamp = registrant.getLastDirtyTimestamp();
                logger.debug("Existing lease found (existing={}, provided={}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);

                // 这是一个>而不是> =，因为如果时间戳相等，我们仍然采用远程传输的InstanceInfo而不是服务器本地副本。
                // this is a > instead of a >= because if the timestamps are equal, we still take the remote transmitted
                // InstanceInfo instead of the server local copy.
                // tip: 如果已存在的脏数据时间 > 注册的脏数据时间，那么保留服务器的
                if (existingLastDirtyTimestamp > registrationLastDirtyTimestamp) {
                    logger.warn("There is an existing lease and the existing lease's dirty timestamp {} is greater" +
                            " than the one that is being registered {}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);
                    logger.warn("Using the existing instanceInfo instead of the new instanceInfo as the registrant");
                    registrant = existingLease.getHolder();
                }
            } else {
                // 租赁不存在的时候，就注册一个新的
                // The lease does not exist and hence it is a new registration
                synchronized (lock) {
                    if (this.expectedNumberOfClientsSendingRenews > 0) {
                        // 由于客户端要注册，因此请增加发送续订的客户端数量
                        // Since the client wants to register it, increase the number of clients sending renews
                        this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews + 1;
                        // 更新每分钟阈值
                        updateRenewsPerMinThreshold();
                    }
                }
                logger.debug("No previous lease information found; it is new registration");
            }

            // 注册的实例信息，leaseDuration 租赁时间90秒，默认
            Lease<InstanceInfo> lease = new Lease<InstanceInfo>(registrant, leaseDuration);
            if (existingLease != null) {
                lease.setServiceUpTimestamp(existingLease.getServiceUpTimestamp());
            }
            gMap.put(registrant.getId(), lease);
            recentRegisteredQueue.add(new Pair<Long, String>(
                    System.currentTimeMillis(),
                    registrant.getAppName() + "(" + registrant.getId() + ")"));
            // 这是发生覆盖状态的初始状态转移的地方
            // This is where the initial state transfer of overridden status happens
            if (!InstanceStatus.UNKNOWN.equals(registrant.getOverriddenStatus())) {
                logger.debug("Found overridden status {} for instance {}. Checking to see if needs to be add to the "
                        + "overrides", registrant.getOverriddenStatus(), registrant.getId());
                // 覆盖实例状态
                if (!overriddenInstanceStatusMap.containsKey(registrant.getId())) {
                    logger.info("Not found overridden id {} and hence adding it", registrant.getId());
                    overriddenInstanceStatusMap.put(registrant.getId(), registrant.getOverriddenStatus());
                }
            }
            // 覆盖实例状态
            InstanceStatus overriddenStatusFromMap = overriddenInstanceStatusMap.get(registrant.getId());
            if (overriddenStatusFromMap != null) {
                logger.info("Storing overridden status {} from map", overriddenStatusFromMap);
                registrant.setOverriddenStatus(overriddenStatusFromMap);
            }

            // 根据覆盖的状态规则设置状态
            // Set the status based on the overridden status rules
            InstanceStatus overriddenInstanceStatus = getOverriddenInstanceStatus(registrant, existingLease, isReplication);
            registrant.setStatusWithoutDirty(overriddenInstanceStatus);

            // 如果租约已注册为UP状态，请设置租约服务启动时间戳记
            // If the lease is registered with UP status, set lease service up timestamp
            if (InstanceStatus.UP.equals(registrant.getStatus())) {
                // 记录一下启动时间
                lease.serviceUp();
            }
            // 设置actionType为add
            registrant.setActionType(ActionType.ADDED);
            // tip：recentlyChangedQueue 用于记录最近更新，增量拉取，全靠它
            recentlyChangedQueue.add(new RecentlyChangedItem(lease));
            // register 最后更新时间
            registrant.setLastUpdatedTimestamp();
            // tip: 默认是有 responseCache的，有些是不需要 cache的，这里就是设置，不需要使用 cache的key
            invalidateCache(registrant.getAppName(), registrant.getVIPAddress(), registrant.getSecureVipAddress());
            logger.info("Registered instance {}/{} with status {} (replication={})",
                    registrant.getAppName(), registrant.getId(), registrant.getStatus(), isReplication);
        } finally {
            // 解锁
            read.unlock();
        }
    }

    /**
     * 关闭一个注册的 Instance
     * <p>
     * Cancels the registration of an instance.
     *
     * <p>通常，它在关闭客户端以通知服务器从流量中删除实例时由客户端调用。 <p>
     *
     * <p>
     * This is normally invoked by a client when it shuts down informing the
     * server to remove the instance from traffic.
     * </p>
     *
     * @param appName       the application name of the application.
     * @param id            the unique identifier of the instance.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the instance was removed from the {@link AbstractInstanceRegistry} successfully, false otherwise.
     */
    @Override
    public boolean cancel(String appName, String id, boolean isReplication) {
        // 内部关闭
        return internalCancel(appName, id, isReplication);
    }

    /**
     * {@link #cancel（String，String，boolean）}方法被{@link PeerAwareInstanceRegistry}覆盖，因此每个取消请求都复制到对等方。
     * 但是，对于在远程对等方中被视为有效取消的过期，这是不希望的，因此不会启动自我保存模式。
     * <p>
     * {@link #cancel(String, String, boolean)} method is overridden by {@link PeerAwareInstanceRegistry}, so each
     * cancel request is replicated to the peers. This is however not desired for expires which would be counted
     * in the remote peers as valid cancellations, so self preservation mode would not kick-in.
     */
    protected boolean internalCancel(String appName, String id, boolean isReplication) {
        // ReentrantReadWriteLock 读取lock
        read.lock();
        try {
            // 取消注册计数 +1
            CANCEL.increment(isReplication);
            // 从 registry 根据 appName 获取注册实例
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            // 记录删除的实例
            Lease<InstanceInfo> leaseToCancel = null;
            if (gMap != null) {
                // 删除注册的实例
                leaseToCancel = gMap.remove(id);
            }

            // tip: recentCanceledQueue 是一个取消的队列
            // tip: overriddenInstanceStatusMap 用于保存服务状态

            // 将取消的实例，添加到 recentCanceledQueue
            recentCanceledQueue.add(new Pair<Long, String>(System.currentTimeMillis(), appName + "(" + id + ")"));
            // 删除实例的状态缓存
            InstanceStatus instanceStatus = overriddenInstanceStatusMap.remove(id);
            if (instanceStatus != null) {
                logger.debug("Removed instance id {} from the overridden map which has value {}", id, instanceStatus.name());
            }
            // leaseToCancel 是我们删除的实例，如果没找到，这里需要 +1
            if (leaseToCancel == null) {
                // 未找到取消 count +1
                CANCEL_NOT_FOUND.increment(isReplication);
                logger.warn("DS: Registry: cancel failed because Lease is not registered for: {}/{}", appName, id);
                return false;
            } else {
                // tip: 这里的 cancel 里面只是更新了一个 evictionTimestamp(驱逐时间)
                leaseToCancel.cancel();
                // 获取的是 InstanceInfo 实例信息
                InstanceInfo instanceInfo = leaseToCancel.getHolder();
                String vip = null;
                String svip = null;
                if (instanceInfo != null) {
                    // 设置 actionType 为 deleted
                    instanceInfo.setActionType(ActionType.DELETED);
                    // 添加到 最近变化queue
                    recentlyChangedQueue.add(new RecentlyChangedItem(leaseToCancel));
                    // 更新最后 更新时间
                    instanceInfo.setLastUpdatedTimestamp();
                    // 这是两个虚拟的 vip 地址，没有设置获取的是ip
                    vip = instanceInfo.getVIPAddress();
                    svip = instanceInfo.getSecureVipAddress();
                }
                // 将缓存失效
                invalidateCache(appName, vip, svip);
                logger.info("Cancelled instance {}/{} (replication={})", appName, id, isReplication);
            }
        } finally {
            read.unlock();
        }
        // tip: 更新一下客户端数量，关闭了一个客户端 这里需要 -1
        synchronized (lock) {
            if (this.expectedNumberOfClientsSendingRenews > 0) {
                // Since the client wants to cancel it, reduce the number of clients to send renews.
                this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews - 1;
                // 更新每分钟阈值(客户端数量，和请求的延迟，这些信息)
                updateRenewsPerMinThreshold();
            }
        }

        return true;
    }

    /**
     * 将给定应用程序名称的给定实例标记为已更新，还标记它是否源自复制。
     *
     * Marks the given instance of the given app name as renewed, and also marks whether it originated from
     * replication.
     *
     * @see com.netflix.eureka.lease.LeaseManager#renew(java.lang.String, java.lang.String, boolean)
     */
    public boolean renew(String appName, String id, boolean isReplication) {
        // 续约次数 +1
        RENEW.increment(isReplication);
        // 根据 appName 获取注册的实例节点信息
        Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);

        // 获取续约的节点
        Lease<InstanceInfo> leaseToRenew = null;
        if (gMap != null) {
            leaseToRenew = gMap.get(id);
        }
        // <1>
        // tip: 没有找到续约的节点，这里会返回一个 NOT_FOUND
        // tip: client 收到 NOT_FOUND，会去调用register进行注册
        if (leaseToRenew == null) {
            RENEW_NOT_FOUND.increment(isReplication);
            logger.warn("DS: Registry: lease doesn't exist, registering resource: {} - {}", appName, id);
            return false;
        } else {
            // tip: 心跳续约

            // tip: getHolder 保存的是我们 client 注册的 InstanceInfo
            InstanceInfo instanceInfo = leaseToRenew.getHolder();
            if (instanceInfo != null) {
                // touchASGCache(instanceInfo.getASGName());
                // 获取覆盖实例状态(这一步)
                InstanceStatus overriddenInstanceStatus = this.getOverriddenInstanceStatus(
                        instanceInfo, leaseToRenew, isReplication);
                // UNKNOWN 是一个未知的状态
                if (overriddenInstanceStatus == InstanceStatus.UNKNOWN) {
                    logger.info("Instance status UNKNOWN possibly due to deleted override for instance {}"
                            + "; re-register required", instanceInfo.getId());
                    RENEW_NOT_FOUND.increment(isReplication);
                    return false;
                }
                if (!instanceInfo.getStatus().equals(overriddenInstanceStatus)) {
                    logger.info(
                            "The instance status {} is different from overridden instance status {} for instance {}. "
                                    + "Hence setting the status to overridden status", instanceInfo.getStatus().name(),
                            overriddenInstanceStatus.name(),
                            instanceInfo.getId());
                    instanceInfo.setStatusWithoutDirty(overriddenInstanceStatus);
                }
            }
            // <2> 续租每分钟次数 +1
            renewsLastMin.increment();
            // <3> 设置 租约最后更新时间（续租）
            leaseToRenew.renew();
            return true;
        }
    }

    /**
     * @param id               the unique identifier of the instance.
     * @param overriddenStatus Overridden status if any.
     * @deprecated this is expensive, try not to use. See if you can use
     * {@link #storeOverriddenStatusIfRequired(String, String, InstanceStatus)} instead.
     * <p>
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     */
    @Deprecated
    @Override
    public void storeOverriddenStatusIfRequired(String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);
        if ((instanceStatus == null)
                || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got restarted -this will help us maintain
            // the overridden state from the replica
            logger.info(
                    "Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            List<InstanceInfo> instanceInfo = this.getInstancesById(id, false);
            if ((instanceInfo != null) && (!instanceInfo.isEmpty())) {
                instanceInfo.iterator().next().setOverriddenStatus(overriddenStatus);
                logger.info(
                        "Setting the overridden status for instance id {} and the value is {} ",
                        id, overriddenStatus.name());

            }
        }
    }

    /**
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     *
     * @param appName          the application name of the instance.
     * @param id               the unique identifier of the instance.
     * @param overriddenStatus overridden status if any.
     */
    @Override
    public void storeOverriddenStatusIfRequired(String appName, String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);
        if ((instanceStatus == null) || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got
            // restarted -this will help us maintain the overridden state
            // from the replica
            logger.info("Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            InstanceInfo instanceInfo = this.getInstanceByAppAndId(appName, id, false);
            instanceInfo.setOverriddenStatus(overriddenStatus);
            logger.info("Set the overridden status for instance (appname:{}, id:{}} and the value is {} ",
                    appName, id, overriddenStatus.name());
        }
    }

    /**
     * Updates the status of an instance. Normally happens to put an instance
     * between {@link InstanceStatus#OUT_OF_SERVICE} and
     * {@link InstanceStatus#UP} to put the instance in and out of traffic.
     *
     * @param appName            the application name of the instance.
     * @param id                 the unique identifier of the instance.
     * @param newStatus          the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication      true if this is a replication event from other nodes, false
     *                           otherwise.
     * @return true if the status was successfully updated, false otherwise.
     */
    @Override
    public boolean statusUpdate(String appName, String id,
                                InstanceStatus newStatus, String lastDirtyTimestamp,
                                boolean isReplication) {
        read.lock();
        try {
            STATUS_UPDATE.increment(isReplication);
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }
            if (lease == null) {
                return false;
            } else {
                lease.renew();
                InstanceInfo info = lease.getHolder();
                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }
                if ((info != null) && !(info.getStatus().equals(newStatus))) {
                    // Mark service as UP if needed
                    if (InstanceStatus.UP.equals(newStatus)) {
                        lease.serviceUp();
                    }
                    // This is NAC overridden status
                    overriddenInstanceStatusMap.put(id, newStatus);
                    // Set it for transfer of overridden status to replica on
                    // replica start up
                    info.setOverriddenStatus(newStatus);
                    long replicaDirtyTimestamp = 0;
                    info.setStatusWithoutDirty(newStatus);
                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.parseLong(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }
                    info.setActionType(ActionType.MODIFIED);
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));
                    info.setLastUpdatedTimestamp();
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * Removes status override for a give instance.
     *
     * @param appName            the application name of the instance.
     * @param id                 the unique identifier of the instance.
     * @param newStatus          the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication      true if this is a replication event from other nodes, false
     *                           otherwise.
     * @return true if the status was successfully updated, false otherwise.
     */
    @Override
    public boolean deleteStatusOverride(String appName, String id,
                                        InstanceStatus newStatus,
                                        String lastDirtyTimestamp,
                                        boolean isReplication) {
        read.lock();
        try {
            STATUS_OVERRIDE_DELETE.increment(isReplication);
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }
            if (lease == null) {
                return false;
            } else {
                lease.renew();
                InstanceInfo info = lease.getHolder();

                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }

                InstanceStatus currentOverride = overriddenInstanceStatusMap.remove(id);
                if (currentOverride != null && info != null) {
                    info.setOverriddenStatus(InstanceStatus.UNKNOWN);
                    info.setStatusWithoutDirty(newStatus);
                    long replicaDirtyTimestamp = 0;
                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.parseLong(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }
                    info.setActionType(ActionType.MODIFIED);
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));
                    info.setLastUpdatedTimestamp();
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * 如果启用了到期，则将实例注册表中已到期的所有内容逐出。
     *
     * Evicts everything in the instance registry that has expired, if expiry is enabled.
     *
     * @see com.netflix.eureka.lease.LeaseManager#evict()
     */
    @Override
    public void evict() {
        // tip: evict 这个驱逐是给接口掉的，直接不给补充时间
        evict(0l);
    }

    public void evict(long additionalLeaseMs) {
        logger.debug("Running the evict task");
        // 没有开启驱逐，就直接 return
        if (!isLeaseExpirationEnabled()) {
            logger.debug("DS: lease expiration is currently disabled.");
            return;
        }

        // 我们首先收集所有过期的物品，以随机顺序将其逐出。
        // 对于大型驱逐集，如果不这样做，我们可能会在自我保护开始之前先清除整个应用程序。
        // 通过将其随机化，其影响应均匀地分布在所有应用程序中。
        // We collect first all expired items, to evict them in random order. For large eviction sets,
        // if we do not that, we might wipe out whole apps before self preservation kicks in. By randomizing it,
        // the impact should be evenly distributed across all applications.

        // <1> 收集需要驱逐的实例，这里会随机驱逐，为什么需要随机驱逐呢? 这是自我保护机制的一种，
        // 如果本次驱逐大于某一个值，就会触发随机驱逐，驱逐的实例过多，会将某些应用实例全部清空
        List<Lease<InstanceInfo>> expiredLeases = new ArrayList<>();
        for (Entry<String, Map<String, Lease<InstanceInfo>>> groupEntry : registry.entrySet()) {
            Map<String, Lease<InstanceInfo>> leaseMap = groupEntry.getValue();
            if (leaseMap != null) {
                for (Entry<String, Lease<InstanceInfo>> leaseEntry : leaseMap.entrySet()) {
                    Lease<InstanceInfo> lease = leaseEntry.getValue();
                    // <1.1> 调用 isExpired 判断是否过期
                    if (lease.isExpired(additionalLeaseMs) && lease.getHolder() != null) {
                        expiredLeases.add(lease);
                    }
                }
            }
        }

        // 为了补偿GC的暂停或本地时间的漂移，我们需要使用当前注册表大小作为触发自我保存的基础。否则，我们将清除完整的注册表。
        // To compensate for GC pauses or drifting local time, we need to use current registry size as a base for
        // triggering self-preservation. Without that we would wipe out full registry.

        // <2> 获取注册的实例(实例，不是应用，一个应用可能多个实例)
        int registrySize = (int) getLocalRegistrySize();
        // <3> 获取续订百分比阈值，默认为85%，如果 registrySize 100个 * 0.85 = 15个
        int registrySizeThreshold = (int) (registrySize * serverConfig.getRenewalPercentThreshold());
        // <4> 驱逐限制 85，最大驱逐就是 85%
        int evictionLimit = registrySize - registrySizeThreshold;
        // <5> 驱逐大小，和驱逐限制，取一个最小值
        // tip: toEvict 就是最终驱逐的数量
        int toEvict = Math.min(expiredLeases.size(), evictionLimit);
        if (toEvict > 0) {
            logger.info("Evicting {} items (expired={}, evictionLimit={})", toEvict, expiredLeases.size(), evictionLimit);

            Random random = new Random(System.currentTimeMillis());
            for (int i = 0; i < toEvict; i++) {
                // <5.1> 选择一个随机项目（Knuth随机算法）
                // Pick a random item (Knuth shuffle algorithm)
                int next = i + random.nextInt(expiredLeases.size() - i);
                // <5.2> 随机打乱 collection，采用 swap交替位置
                Collections.swap(expiredLeases, i, next);
                Lease<InstanceInfo> lease = expiredLeases.get(i);

                //
                String appName = lease.getHolder().getAppName();
                String id = lease.getHolder().getId();
                EXPIRED.increment();
                logger.warn("DS: Registry: expired lease for {}/{}", appName, id);
                // <5.3> 取消实例，这里就去 register 删除实例了
                internalCancel(appName, id, false);
            }
        }
    }


    /**
     * Returns the given app that is in this instance only, falling back to other regions transparently only
     * if specified in this client configuration.
     *
     * @param appName the application name of the application
     * @return the application
     * @see com.netflix.discovery.shared.LookupService#getApplication(java.lang.String)
     */
    @Override
    public Application getApplication(String appName) {
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();
        return this.getApplication(appName, !disableTransparentFallback);
    }

    /**
     * Get application information.
     *
     * @param appName             The name of the application
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the application
     */
    @Override
    public Application getApplication(String appName, boolean includeRemoteRegion) {
        Application app = null;

        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);

        if (leaseMap != null && leaseMap.size() > 0) {
            for (Entry<String, Lease<InstanceInfo>> entry : leaseMap.entrySet()) {
                if (app == null) {
                    app = new Application(appName);
                }
                app.addInstance(decorateInstanceInfo(entry.getValue()));
            }
        } else if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    return application;
                }
            }
        }
        return app;
    }

    /**
     * 在此实例注册表中获取所有应用程序，如果Eureka配置允许，则退回到其他区域。
     *
     * Get all applications in this instance registry, falling back to other regions if allowed in the Eureka config.
     *
     * @return the list of all known applications
     * @see com.netflix.discovery.shared.LookupService#getApplications()
     */
    public Applications getApplications() {
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();
        if (disableTransparentFallback) {
            // 本地获取 applications
            return getApplicationsFromLocalRegionOnly();
        } else {
            // 云服务器的 eureka 集群中获取 applications
            return getApplicationsFromAllRemoteRegions();  // Behavior of falling back to remote region can be disabled.
        }
    }

    /**
     * Returns applications including instances from all remote regions. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with a <code>null</code> argument.
     */
    public Applications getApplicationsFromAllRemoteRegions() {
        return getApplicationsFromMultipleRegions(allKnownRemoteRegions);
    }

    /**
     * Returns applications including instances from local region only. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with an empty array.
     */
    @Override
    public Applications getApplicationsFromLocalRegionOnly() {
        return getApplicationsFromMultipleRegions(EMPTY_STR_ARRAY);
    }

    /**
     * This method will return applications with instances from all passed remote regions as well as the current region.
     * Thus, this gives a union view of instances from multiple regions. <br/>
     * The application instances for which this union will be done can be restricted to the names returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for every region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     * If you are not selectively requesting for a remote region, use {@link #getApplicationsFromAllRemoteRegions()}
     * or {@link #getApplicationsFromLocalRegionOnly()}
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> or empty no remote regions are
     *                      included.
     * @return The applications with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be only for certain whitelisted apps as explained above.
     */
    public Applications getApplicationsFromMultipleRegions(String[] remoteRegions) {
        // 是否包含 远程注册服务
        boolean includeRemoteRegion = null != remoteRegions && remoteRegions.length != 0;

        logger.debug("Fetching applications registry with remote regions: {}, Regions argument {}",
                includeRemoteRegion, remoteRegions);
        // 计数+1
        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS.increment();
        } else {
            GET_ALL_CACHE_MISS.increment();
        }
        // 声明返回的 Applications 容器，version 默认1开始
        Applications apps = new Applications();
        apps.setVersion(1L);

        // tip：这里是处理 本地的register
        // tip：本地的 register + includeRemoteRegion 才是最终更新的

        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;
            if (entry.getValue() != null) {
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {
                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();
                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }
                    app.addInstance(decorateInstanceInfo(lease));
                }
            }

            if (app != null) {
                apps.addApplication(app);
            }
        }

        // tip：这里是处理 远程eurekaServer
        if (includeRemoteRegion) {
            for (String remoteRegion : remoteRegions) {
                // 远程注册中心信息(这里是一个 map，因为远程注册的信息也是定时更新的，采用的也是缓存)
                RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                if (null != remoteRegistry) {
                    // 获取远程注册中心的，applications 信息
                    Applications remoteApps = remoteRegistry.getApplications();
                    for (Application application : remoteApps.getRegisteredApplications()) {
                        // 应该从远程注册表获取
                        if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                            logger.info("Application {}  fetched from the remote region {}",
                                    application.getName(), remoteRegion);

                            Application appInstanceTillNow = apps.getRegisteredApplications(application.getName());
                            if (appInstanceTillNow == null) {
                                appInstanceTillNow = new Application(application.getName());
                                apps.addApplication(appInstanceTillNow);
                            }
                            for (InstanceInfo instanceInfo : application.getInstances()) {
                                appInstanceTillNow.addInstance(instanceInfo);
                            }
                        } else {
                            logger.debug("Application {} not fetched from the remote region {} as there exists a "
                                            + "whitelist and this app is not in the whitelist.",
                                    application.getName(), remoteRegion);
                        }
                    }
                } else {
                    logger.warn("No remote registry available for the remote region {}", remoteRegion);
                }
            }
        }
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    private boolean shouldFetchFromRemoteRegistry(String appName, String remoteRegion) {
        Set<String> whiteList = serverConfig.getRemoteRegionAppWhitelist(remoteRegion);
        if (null == whiteList) {
            whiteList = serverConfig.getRemoteRegionAppWhitelist(null); // see global whitelist.
        }
        return null == whiteList || whiteList.contains(appName);
    }

    /**
     * Get the registry information about all {@link Applications}.
     *
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return applications
     * @deprecated Use {@link #getApplicationsFromMultipleRegions(String[])} instead. This method has a flawed behavior
     * of transparently falling back to a remote region if no instances for an app is available locally. The new
     * behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplications(boolean includeRemoteRegion) {
        GET_ALL_CACHE_MISS.increment();
        Applications apps = new Applications();
        apps.setVersion(1L);
        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;

            if (entry.getValue() != null) {
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {

                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();

                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }

                    app.addInstance(decorateInstanceInfo(lease));
                }
            }
            if (app != null) {
                apps.addApplication(app);
            }
        }
        if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Applications applications = remoteRegistry.getApplications();
                for (Application application : applications
                        .getRegisteredApplications()) {
                    Application appInLocalRegistry = apps
                            .getRegisteredApplications(application.getName());
                    if (appInLocalRegistry == null) {
                        apps.addApplication(application);
                    }
                }
            }
        }
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    /**
     * 获取增量信息
     *
     * Get the registry information about the delta changes. The deltas are
     * cached for a window specified by
     * {@link EurekaServerConfig#getRetentionTimeInMSInDeltaQueue()}. Subsequent
     * requests for delta information may return the same information and client
     * must make sure this does not adversely affect them.
     *
     * @return all application deltas.
     * @deprecated use {@link #getApplicationDeltasFromMultipleRegions(String[])} instead. This method has a
     * flawed behavior of transparently falling back to a remote region if no instances for an app is available locally.
     * The new behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplicationDeltas() {
        GET_ALL_CACHE_MISS_DELTA.increment();
        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDelta().get());
        Map<String, Application> applicationInstancesMap = new HashMap<String, Application>();
        write.lock();
        try {
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is : {}",
                    this.recentlyChangedQueue.size());
            while (iter.hasNext()) {
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug(
                        "The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                Application app = applicationInstancesMap.get(instanceInfo
                        .getAppName());
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }

            boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();

            if (!disableTransparentFallback) {
                Applications allAppsInLocalRegion = getApplications(false);

                for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                    Applications applications = remoteRegistry.getApplicationDeltas();
                    for (Application application : applications.getRegisteredApplications()) {
                        Application appInLocalRegistry =
                                allAppsInLocalRegion.getRegisteredApplications(application.getName());
                        if (appInLocalRegistry == null) {
                            apps.addApplication(application);
                        }
                    }
                }
            }

            Applications allApps = getApplications(!disableTransparentFallback);
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the application delta also including instances from the passed remote regions, with the instances from the
     * local region. <br/>
     * <p>
     * The remote regions from where the instances will be chosen can further be restricted if this application does not
     * appear in the whitelist specified for the region as returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for a region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> all remote regions are included.
     *                      If empty list then no remote region is included.
     * @return The delta with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be further be restricted as explained above. <code>null</code> if the application does
     * not exist locally or in remote regions.
     */
    public Applications getApplicationDeltasFromMultipleRegions(String[] remoteRegions) {
        if (null == remoteRegions) {
            remoteRegions = allKnownRemoteRegions; // null means all remote regions.
        }

        boolean includeRemoteRegion = remoteRegions.length != 0;

        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS_DELTA.increment();
        } else {
            GET_ALL_CACHE_MISS_DELTA.increment();
        }

        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDeltaWithRegions().get());
        Map<String, Application> applicationInstancesMap = new HashMap<String, Application>();
        write.lock();
        try {
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is :{}", this.recentlyChangedQueue.size());
            while (iter.hasNext()) {
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug("The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                Application app = applicationInstancesMap.get(instanceInfo.getAppName());
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }

            if (includeRemoteRegion) {
                for (String remoteRegion : remoteRegions) {
                    RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                    if (null != remoteRegistry) {
                        Applications remoteAppsDelta = remoteRegistry.getApplicationDeltas();
                        if (null != remoteAppsDelta) {
                            for (Application application : remoteAppsDelta.getRegisteredApplications()) {
                                if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                                    Application appInstanceTillNow =
                                            apps.getRegisteredApplications(application.getName());
                                    if (appInstanceTillNow == null) {
                                        appInstanceTillNow = new Application(application.getName());
                                        apps.addApplication(appInstanceTillNow);
                                    }
                                    for (InstanceInfo instanceInfo : application.getInstances()) {
                                        appInstanceTillNow.addInstance(new InstanceInfo(instanceInfo));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Applications allApps = getApplicationsFromMultipleRegions(remoteRegions);
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id      the unique identifier of the instance.
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id) {
        return this.getInstanceByAppAndId(appName, id, true);
    }

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName              the application name for which the information is requested.
     * @param id                   the unique identifier of the instance.
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id, boolean includeRemoteRegions) {
        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);
        Lease<InstanceInfo> lease = null;
        if (leaseMap != null) {
            lease = leaseMap.get(id);
        }
        if (lease != null
                && (!isLeaseExpirationEnabled() || !lease.isExpired())) {
            return decorateInstanceInfo(lease);
        } else if (includeRemoteRegions) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    return application.getByInstanceId(id);
                }
            }
        }
        return null;
    }

    /**
     * @see com.netflix.discovery.shared.LookupService#getInstancesById(String)
     * @deprecated Try {@link #getInstanceByAppAndId(String, String)} instead.
     * <p>
     * Get all instances by ID, including automatically asking other regions if the ID is unknown.
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id) {
        return this.getInstancesById(id, true);
    }

    /**
     * @param id                   the unique id of the instance
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return list of InstanceInfo objects.
     * @deprecated Try {@link #getInstanceByAppAndId(String, String, boolean)} instead.
     * <p>
     * Get the list of instances by its unique id.
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id, boolean includeRemoteRegions) {
        List<InstanceInfo> list = new ArrayList<InstanceInfo>();

        for (Iterator<Entry<String, Map<String, Lease<InstanceInfo>>>> iter =
             registry.entrySet().iterator(); iter.hasNext(); ) {

            Map<String, Lease<InstanceInfo>> leaseMap = iter.next().getValue();
            if (leaseMap != null) {
                Lease<InstanceInfo> lease = leaseMap.get(id);

                if (lease == null || (isLeaseExpirationEnabled() && lease.isExpired())) {
                    continue;
                }

                if (list == Collections.EMPTY_LIST) {
                    list = new ArrayList<InstanceInfo>();
                }
                list.add(decorateInstanceInfo(lease));
            }
        }
        if (list.isEmpty() && includeRemoteRegions) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                for (Application application : remoteRegistry.getApplications()
                        .getRegisteredApplications()) {
                    InstanceInfo instanceInfo = application.getByInstanceId(id);
                    if (instanceInfo != null) {
                        list.add(instanceInfo);
                        return list;
                    }
                }
            }
        }
        return list;
    }

    private InstanceInfo decorateInstanceInfo(Lease<InstanceInfo> lease) {
        InstanceInfo info = lease.getHolder();

        // client app settings
        int renewalInterval = LeaseInfo.DEFAULT_LEASE_RENEWAL_INTERVAL;
        int leaseDuration = LeaseInfo.DEFAULT_LEASE_DURATION;

        // TODO: clean this up
        if (info.getLeaseInfo() != null) {
            renewalInterval = info.getLeaseInfo().getRenewalIntervalInSecs();
            leaseDuration = info.getLeaseInfo().getDurationInSecs();
        }

        info.setLeaseInfo(LeaseInfo.Builder.newBuilder()
                .setRegistrationTimestamp(lease.getRegistrationTimestamp())
                .setRenewalTimestamp(lease.getLastRenewalTimestamp())
                .setServiceUpTimestamp(lease.getServiceUpTimestamp())
                .setRenewalIntervalInSecs(renewalInterval)
                .setDurationInSecs(leaseDuration)
                .setEvictionTimestamp(lease.getEvictionTimestamp()).build());

        info.setIsCoordinatingDiscoveryServer();
        return info;
    }

    /**
     * Servo route; do not call.
     *
     * @return servo data
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsInLastMin",
            description = "Number of total heartbeats received in the last minute", type = DataSourceType.GAUGE)
    @Override
    public long getNumOfRenewsInLastMin() {
        return renewsLastMin.getCount();
    }


    /**
     * 获取每分钟续订的阈值。
     * <p>
     * Gets the threshold for the renewals per minute.
     *
     * @return the integer representing the threshold for the renewals per
     * minute.
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsPerMinThreshold", type = DataSourceType.GAUGE)
    @Override
    public int getNumOfRenewsPerMinThreshold() {
        return numberOfRenewsPerMinThreshold;
    }

    /**
     * Get the N instances that are most recently registered.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNRegisteredInstances() {
        List<Pair<Long, String>> list = new ArrayList<Pair<Long, String>>(recentRegisteredQueue);
        Collections.reverse(list);
        return list;
    }

    /**
     * Get the N instances that have most recently canceled.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNCanceledInstances() {
        List<Pair<Long, String>> list = new ArrayList<Pair<Long, String>>(recentCanceledQueue);
        Collections.reverse(list);
        return list;
    }

    private void invalidateCache(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress) {
        // invalidate cache
        responseCache.invalidate(appName, vipAddress, secureVipAddress);
    }

    protected void updateRenewsPerMinThreshold() {

        // tip：这里计算的是 eureka server 当前的阀值
        // tip: 默认 getRenewalPercentThreshold 最大为 85%

        // tip: 客户端心跳时间(默认30秒) / 60 = 2
        double d1 = (60.0 / serverConfig.getExpectedClientRenewalIntervalSeconds());
        // tip: 客户端链接数量 * d1
        double d2 = this.expectedNumberOfClientsSendingRenews * d1;
        // tip: eureka server 配置的百分比阀值，计算每分钟阀值(d2 * eurekaServer配置的百分比阀值)
        this.numberOfRenewsPerMinThreshold = (int) (d2 * serverConfig.getRenewalPercentThreshold());
//        this.numberOfRenewsPerMinThreshold =
//                (int) (this.expectedNumberOfClientsSendingRenews
//                        * (60.0 / serverConfig.getExpectedClientRenewalIntervalSeconds())
//                        * serverConfig.getRenewalPercentThreshold());
    }

    private static final class RecentlyChangedItem {
        private long lastUpdateTime;
        private Lease<InstanceInfo> leaseInfo;

        public RecentlyChangedItem(Lease<InstanceInfo> lease) {
            // 租赁信息
            this.leaseInfo = lease;
            // 租赁的时间
            lastUpdateTime = System.currentTimeMillis();
        }

        public long getLastUpdateTime() {
            return this.lastUpdateTime;
        }

        public Lease<InstanceInfo> getLeaseInfo() {
            return this.leaseInfo;
        }
    }

    protected void postInit() {
        // 心跳续约统计任务
        renewsLastMin.start();
        if (evictionTaskRef.get() != null) {
            evictionTaskRef.get().cancel();
        }
        // 驱逐任务
        evictionTaskRef.set(new EvictionTask());
        evictionTimer.schedule(evictionTaskRef.get(),
                serverConfig.getEvictionIntervalTimerInMs(),
                serverConfig.getEvictionIntervalTimerInMs());
    }

    /**
     * Perform all cleanup and shutdown operations.
     */
    @Override
    public void shutdown() {
        deltaRetentionTimer.cancel();
        evictionTimer.cancel();
        renewsLastMin.stop();
        responseCache.stop();
    }

    @com.netflix.servo.annotations.Monitor(name = "numOfElementsinInstanceCache", description = "Number of overrides in the instance Cache", type = DataSourceType.GAUGE)
    public long getNumberofElementsininstanceCache() {
        return overriddenInstanceStatusMap.size();
    }

    /**
     * 驱逐任务
     */
    /* visible for testing */
    class EvictionTask extends TimerTask {

        private final AtomicLong lastExecutionNanosRef = new AtomicLong(0l);

        @Override
        public void run() {
            try {
                // <1> tip：compensationTimeMs 是补偿时间（时钟偏移，gc，服务器之间同步）
                long compensationTimeMs = getCompensationTimeMs();
                logger.info("Running the evict task with compensationTime {}ms", compensationTimeMs);
                // <2> 调用驱逐
                evict(compensationTimeMs);
            } catch (Throwable e) {
                logger.error("Could not run the evict task", e);
            }
        }

        /**
         * 计算补偿时间，该补偿时间定义为自上次迭代以来此任务实际执行的时间，与配置的执行时间之比。
         * 当时间变化（例如由于时钟偏斜或gc）导致实际驱逐任务的执行时间晚于所需时间（根据配置的周期）时，此功能很有用。
         *
         * compute a compensation time defined as the actual time this task was executed since the prev iteration,
         * vs the configured amount of time for execution. This is useful for cases where changes in time (due to
         * clock skew or gc for example) causes the actual eviction task to execute later than the desired time
         * according to the configured cycle.
         */
        long getCompensationTimeMs() {
            // tip: 补偿时间计算

            // <1> 获取当前时间(纳米时间)
            long currNanos = getCurrentTimeNano();
            // 上一次驱逐时间
            // tip: 这里是 getAndSet 首次是0，下一次就是上一次驱逐时间
            // tip: 0 就代表不会补偿
            long lastNanos = lastExecutionNanosRef.getAndSet(currNanos);
            if (lastNanos == 0l) {
                return 0l;
            }
            // <2> 相差时间 = 当前时间和上一次执行时间
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(currNanos - lastNanos);
            // <3> 配置的驱逐时间
            long compensationTime = elapsedMs - serverConfig.getEvictionIntervalTimerInMs();
            // <4> 情况好的话为小于 0，所以返回0，不好的情况下会补充时间
            return compensationTime <= 0l ? 0l : compensationTime;
        }

        long getCurrentTimeNano() {  // for testing
            return System.nanoTime();
        }

    }

    /* visible for testing */ static class CircularQueue<E> extends AbstractQueue<E> {

        private final ArrayBlockingQueue<E> delegate;
        private final int capacity;

        public CircularQueue(int capacity) {
            this.capacity = capacity;
            this.delegate = new ArrayBlockingQueue<>(capacity);
        }

        @Override
        public Iterator<E> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean offer(E e) {
            while (!delegate.offer(e)) {
                delegate.poll();
            }
            return true;
        }

        @Override
        public E poll() {
            return delegate.poll();
        }

        @Override
        public E peek() {
            return delegate.peek();
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public Object[] toArray() {
            return delegate.toArray();
        }
    }

    /**
     * @return The rule that will process the instance status override.
     */
    protected abstract InstanceStatusOverrideRule getInstanceInfoOverrideRule();

    protected InstanceInfo.InstanceStatus getOverriddenInstanceStatus(InstanceInfo r,
                                                                      Lease<InstanceInfo> existingLease,
                                                                      boolean isReplication) {
        InstanceStatusOverrideRule rule = getInstanceInfoOverrideRule();
        logger.debug("Processing override status using rule: {}", rule);
        return rule.apply(r, existingLease, isReplication).status();
    }

    private TimerTask getDeltaRetentionTask() {

        // tip: recentlyChangedQueue 是一个无边界的队列
        // tip: recentlyChangedQueue 只保存近3分钟更新的(默认是三分钟) instance信息
        // tip: 这里是通过 TimerTask 来进行处理，过期删除的动作

        return new TimerTask() {
            @Override
            public void run() {
                // 获取队列注册的所有信息
                Iterator<RecentlyChangedItem> it = recentlyChangedQueue.iterator();
                while (it.hasNext()) {
                    // 当前instance最后更新的时间，是否大于现在时间 + 延时时间(3分钟)，大于3分钟直接 remove
                    if (it.next().getLastUpdateTime() <
                            System.currentTimeMillis() - serverConfig.getRetentionTimeInMSInDeltaQueue()) {
                        it.remove();
                    } else {
                        break;
                    }
                }
            }
        };
    }
}
