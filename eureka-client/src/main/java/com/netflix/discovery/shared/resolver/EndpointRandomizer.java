package com.netflix.discovery.shared.resolver;

import java.util.List;

/**
 * 端点随机生成
 */
public interface EndpointRandomizer {
    <T extends EurekaEndpoint> List<T> randomize(List<T> list);
}
