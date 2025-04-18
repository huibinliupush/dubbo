/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.support.registry;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.cluster.support.AbstractClusterInvoker;
import org.apache.dubbo.rpc.cluster.support.wrapper.MockClusterInvoker;

import java.util.List;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.PREFERRED_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_ZONE;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_ZONE_FORCE;
import static org.apache.dubbo.common.constants.RegistryConstants.ZONE_KEY;

/**
 * When there're more than one registry for subscription.
 *
 * This extension provides a strategy to decide how to distribute traffics among them:
 * 1. registry marked as 'preferred=true' has the highest priority.
 * 2. check the zone the current request belongs, pick the registry that has the same zone first.
 * 3. Evenly balance traffic between all registries based on each registry's weight.
 * 4. Pick anyone that's available.
 */
public class ZoneAwareClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(ZoneAwareClusterInvoker.class);

    // CLUSTER.join(new StaticDirectory(u, invokers));
    // invokers 是具体的 clusterInvoker 封装具体注册中心的 provider , routeChain , loanbalance
    // u 为 registerUrl
    public ZoneAwareClusterInvoker(Directory<T> directory) {
        super(directory);// StaticDirectory
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        // First, pick the invoker (XXXClusterInvoker) that comes from the local registry, distinguish by a 'preferred' key.
        for (Invoker<T> invoker : invokers) {
            // FIXME, the invoker is a cluster invoker representing one Registry, so it will automatically wrapped by MockClusterInvoker.
            // 普通的 ClusterInvoker 会被 MockClusterInvoker 进行包装
            // see： MockClusterWrapper
            MockClusterInvoker<T> mockClusterInvoker = (MockClusterInvoker<T>) invoker;
            // 优先使用 <registry > 配置中配置了 preferred=true 的注册中心
            if (mockClusterInvoker.isAvailable() && mockClusterInvoker.getRegistryUrl()
                    .getParameter(REGISTRY_KEY + "." + PREFERRED_KEY, false)) {
                return mockClusterInvoker.invoke(invocation);
            }
        }

        // providers in the registry with the same zone
        String zone = (String) invocation.getAttachment(REGISTRY_ZONE);
        if (StringUtils.isNotEmpty(zone)) {
            // 选择与 invocation 中的 registry_zone 配置与注册中心 <registry > 相同的 zone 配置
            for (Invoker<T> invoker : invokers) {
                MockClusterInvoker<T> mockClusterInvoker = (MockClusterInvoker<T>) invoker;
                if (mockClusterInvoker.isAvailable() && zone.equals(mockClusterInvoker.getRegistryUrl().getParameter(REGISTRY_KEY + "." + ZONE_KEY))) {
                    return mockClusterInvoker.invoke(invocation);
                }
            }
            // 如果请求的 zone 在多个注册中心中都没有配置（不同的 zone）
            // 如果设置了强制 REGISTRY_ZONE_FORCE 按照 zone 来选取那么就报错
            String force = (String) invocation.getAttachment(REGISTRY_ZONE_FORCE);
            if (StringUtils.isNotEmpty(force) && "true".equalsIgnoreCase(force)) {
                throw new IllegalStateException("No registry instance in zone or no available providers in the registry, zone: "
                        + zone
                        + ", registries: " + invokers.stream().map(invoker -> ((MockClusterInvoker<T>) invoker).getRegistryUrl().toString()).collect(Collectors.joining(",")));
            }
        }


        // load balance among all registries, with registry weight count in.
        // 按照 <registry > 的权重 weight 来选取具体的 registry
        Invoker<T> balancedInvoker = select(loadbalance, invocation, invokers, null);
        if (balancedInvoker.isAvailable()) {
            return balancedInvoker.invoke(invocation);
        }

        // If none of the invokers has a preferred signal or is picked by the loadbalancer, pick the first one available.
        // 选取第一个可用的
        for (Invoker<T> invoker : invokers) {
            MockClusterInvoker<T> mockClusterInvoker = (MockClusterInvoker<T>) invoker;
            if (mockClusterInvoker.isAvailable()) {
                return mockClusterInvoker.invoke(invocation);
            }
        }
        throw new RpcException("No provider available in " + invokers);
    }

}
