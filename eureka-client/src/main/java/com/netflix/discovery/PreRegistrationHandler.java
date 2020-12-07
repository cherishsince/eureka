package com.netflix.discovery;

/**
 * 可以在创建时向{@link EurekaClient}注册以执行预注册逻辑的处理程序。预注册逻辑需要同步，以确保在注册之前执行。
 *
 * A handler that can be registered with an {@link EurekaClient} at creation time to execute
 * pre registration logic. The pre registration logic need to be synchronous to be guaranteed
 * to execute before registration.
 */
public interface PreRegistrationHandler {

    void beforeRegistration();

}
