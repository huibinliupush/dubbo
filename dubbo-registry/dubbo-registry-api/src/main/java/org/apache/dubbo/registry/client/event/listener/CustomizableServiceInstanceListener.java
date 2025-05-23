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
package org.apache.dubbo.registry.client.event.listener;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.event.EventListener;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.client.ServiceInstanceCustomizer;
import org.apache.dubbo.registry.client.event.ServiceInstancePreRegisteredEvent;

/**
 * Customize the {@link ServiceInstance} before registering to Registry.
 *
 * @since 2.7.5
 */
public class CustomizableServiceInstanceListener implements EventListener<ServiceInstancePreRegisteredEvent> {

    /**
     * 初始化 ServiceInstance 的元数据 metadata
     * 用于向注册中心注册的应用级数据就在这里设置
     * ServiceInstanceMetadataCustomizer 为所有初始化 metadataCustomizer 的基类
     *
     * ExportedServicesRevisionMetadataCustomizer 负责添加 dubbo.exported-services.revision
     * 将元数据中心中所有暴露的 exportedURLs （MetadataService除外）计算出一个 revision 值
     *
     * ServiceInstancePortCustomizer 逻辑很简单，如果 serviceInstance 中没有设置 port , 那么就从 ProtocolConfig 中选取一个 port (rest协议优先)
     *
     * MetadataServiceURLParamsMetadataCustomizer 负责添加 dubbo.metadata-service.url-params -> MetadataService协议 : {ParamKey : ParamKeyValue}
     * 将 MetadataServiceURL 中的参数提取出来设置到 metadata 中
     *
     * ProtocolPortsMetadataCustomizer 负责添加 dubbo.endpoints，收集所有协议与 port 之间的对应关系：{protocol:port}
     *
     *
     * SubscribedServicesRevisionMetadataCustomizer 负责添加 dubbo.subscribed-services.revision
     * 一个应用既可以是 provider 也可以是 consumer, 作为 consumer 来说，它所订阅的所有服务的 url 存放在元数据中心的 subscribedURLs 中
     * 计算 subscribedURLs 集合的 revision
     *
     * RefreshServiceMetadataCustomizer： 主要用于元数据中心的 remote 模式， local 模式实现为空
     * */
    @Override
    public void onEvent(ServiceInstancePreRegisteredEvent event) {
        ExtensionLoader<ServiceInstanceCustomizer> loader =
                ExtensionLoader.getExtensionLoader(ServiceInstanceCustomizer.class);
        // FIXME, sort customizer before apply
        loader.getSupportedExtensionInstances().forEach(customizer -> {
            // customizes
            customizer.customize(event.getServiceInstance());
        });
    }
}
