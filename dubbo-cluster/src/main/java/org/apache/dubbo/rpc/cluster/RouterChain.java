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
package org.apache.dubbo.rpc.cluster;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Router chain
 */
public class RouterChain<T> {

    // full list of addresses from registry, classified by method name.
    // RouterChanin 中的 invokers 也是会动态更新的
    // see : setInvokers 方法
    private List<Invoker<T>> invokers = Collections.emptyList();

    // containing all routers, reconstruct every time 'route://' urls change.
    // 包括所有的 routers ：内置（永远不变） ， admin 中配置的路由 router(动态变化)
    private volatile List<Router> routers = Collections.emptyList();

    // Fixed router instances: ConfigConditionRouter, TagRouter, e.g., the rule for each instance may change but the
    // instance will never delete or recreate.
    // 内置 Routers
    private List<Router> builtinRouters = Collections.emptyList();

    public static <T> RouterChain<T> buildChain(URL url) {
        return new RouterChain<>(url);
    }

    private RouterChain(URL url) {
        List<RouterFactory> extensionFactories = ExtensionLoader.getExtensionLoader(RouterFactory.class)
                .getActivateExtension(url, "router");
        // 构建内置：MockRouter , TagRouter , AppRouter , ServiceRouter
        List<Router> routers = extensionFactories.stream()
                .map(factory -> factory.getRouter(url))
                .collect(Collectors.toList());
        // TagRouter : 标签路由，订阅配置中心的 providerApplicationName.tag-router 文件
        // 监听 /dubbo/config/dubbo/providerApplication.tag-router 动态路由配置文件
        // 什么时候订阅呢 ？ 当 RegistryDirectory 第一次向注册中心拉取 providers 或者 provider 变动，注册中心通知变动的时候
        // 都会重新生成 invokers , 随后会更新 RouterChain 中的 invokers
        // see : org.apache.dubbo.rpc.cluster.RouterChain.setInvokers

        // 在 RouterChain.setInvokers 会触发 router 的 notify 方法，notify 中会第一次向配置中心拉取 providerApplicationName.tag-router 动态路由
        // 并监听动态路由的变化

        // AppRouter : consumer 应用级条件路由，创建 AppRouter 的时候在父类 ListenableRouter.init 中订阅动态路由
        // 监听 /dubbo/config/dubbo/consumerApplication.condition-router 动态条件路由配置文件

        // ServiceRouter : reference 级条件路由，创建 ServiceRouter 的时候在父类 ListenableRouter.init 中订阅动态路由
        // 监听 /dubbo/config/dubbo/{interfaceName}:[version]:[group].condition-router
        initWithRouters(routers);
    }

    /**
     * the resident routers must being initialized before address notification.
     * FIXME: this method should not be public
     */
    public void initWithRouters(List<Router> builtinRouters) {
        this.builtinRouters = builtinRouters;
        this.routers = new ArrayList<>(builtinRouters);
        this.sort();
    }

    /**
     * If we use route:// protocol in version before 2.7.0, each URL will generate a Router instance, so we should
     * keep the routers up to date, that is, each time router URLs changes, we should update the routers list, only
     * keep the builtinRouters which are available all the time and the latest notified routers which are generated
     * from URLs.
     *
     * @param routers routers from 'router://' rules in 2.6.x or before.
     */
    public void addRouters(List<Router> routers) {
        List<Router> newRouters = new ArrayList<>();
        newRouters.addAll(builtinRouters);
        newRouters.addAll(routers);
        CollectionUtils.sort(newRouters);
        this.routers = newRouters;
    }

    private void sort() {
        Collections.sort(routers);
    }

    /**
     *
     * @param url
     * @param invocation
     * @return
     */
    public List<Invoker<T>> route(URL url, Invocation invocation) {
        List<Invoker<T>> finalInvokers = invokers;
        for (Router router : routers) {
            finalInvokers = router.route(finalInvokers, url, invocation);
        }
        return finalInvokers;
    }

    /**
     * Notify router chain of the initial addresses from registry at the first time.
     * Notify whenever addresses in registry change.
     *
     * consumer 第一次向注册中心发起订阅的时候，会全量拉取 providers，转换为 invokers 会调用到这里
     * 当注册中心中的 providers 发生变化的时候，consumer 会重新拉取 provider 重新生成 invokers , 也会调用到这里
     * RouterChanin 中的 invokers 也是会动态更新的
     */
    public void setInvokers(List<Invoker<T>> invokers) {
        this.invokers = (invokers == null ? Collections.emptyList() : invokers);
        // 触发 routers 向配置中心订阅动态路由规则
        routers.forEach(router -> router.notify(this.invokers));
    }
}
