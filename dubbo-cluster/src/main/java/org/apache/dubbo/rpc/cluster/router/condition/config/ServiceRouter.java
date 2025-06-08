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
package org.apache.dubbo.rpc.cluster.router.condition.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;

/**
 * Service level router, "server-unique-name.condition-router" 条件路由的生效范围为：指定的 service 服务
 *
 * 该条件路由仅对该 service 生效，多个不同的 consumer 应用，只要包含对 service 的访问均生效
 *
 * 而应用级条件路由 AppRouter 仅针对某个 conmsumer 应用生效路由规则
 *
 * 针对条件路由，我们通常推荐配置 scope: service 的规则，因为它可以跨消费端应用对所有消费特定服务 (service) 的应用生效。
 *
 * 条件路由规则还支持设置具体的机器地址如 ip 或 port，这种情况下使用条件路由可以处理一些开发或线上机器的临时状况，实现黑名单、白名单、实例临时摘除等运维效果
 * => host != 172.22.3.91   将机器 172.22.3.91 从服务的可用列表中排除
 *
 * 白名单 ： host = 172.22.3.91 , 172.22.3.92 , 172.22.3.93
 * 黑名单 ： host != 172.22.3.91 , 172.22.3.92 , 172.22.3.93
 */
public class ServiceRouter extends ListenableRouter {
    public static final String NAME = "SERVICE_ROUTER";
    /**
     * ServiceRouter should before AppRouter
     */
    private static final int SERVICE_ROUTER_DEFAULT_PRIORITY = 140;

    public ServiceRouter(URL url) {
        // ServiceRouter : 监听 /dubbo/config/dubbo/{interfaceName}:[version]:[group].condition-router
        // 不同的 consumer 引用均会监听这个配置
        super(url, DynamicConfiguration.getRuleKey(url));
        this.priority = SERVICE_ROUTER_DEFAULT_PRIORITY;
    }
}
