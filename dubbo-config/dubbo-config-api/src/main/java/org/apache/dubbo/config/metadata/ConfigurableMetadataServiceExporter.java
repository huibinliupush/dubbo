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
package org.apache.dubbo.config.metadata;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.metadata.MetadataServiceExporter;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.emptyList;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;

/**
 * {@link MetadataServiceExporter} implementation based on {@link ConfigManager Dubbo configurations}, the clients
 * should make sure the {@link ApplicationConfig}, {@link RegistryConfig} and {@link ProtocolConfig} are ready before
 * {@link #export()}.
 * <p>
 * Typically, do not worry about their ready status, because they are initialized before
 * any {@link ServiceConfig} exports, or The Dubbo export will be failed.
 * <p>
 * Being aware of it's not a thread-safe implementation.
 *
 * @see MetadataServiceExporter
 * @see ServiceConfig
 * @see ConfigManager
 * @since 2.7.5
 */
public class ConfigurableMetadataServiceExporter implements MetadataServiceExporter {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    // 元数据中心本地模式：InMemoryWritableMetadataService
    // 元数据中心远程模式：RemoteWritableMetadataServiceDelegate
    // 本地 or 远端就看有没有配置 MetadataConfig
    private final MetadataService metadataService;

    private volatile ServiceConfig<MetadataService> serviceConfig;

    public ConfigurableMetadataServiceExporter(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Override
    public ConfigurableMetadataServiceExporter export() {

        if (!isExported()) {

            ServiceConfig<MetadataService> serviceConfig = new ServiceConfig<>();
            serviceConfig.setApplication(getApplicationConfig());
            serviceConfig.setRegistries(getRegistries()); // RegistryConfig 配置（和正常的服务暴露一样，原有的配置）
            // 优化：MetadataService 暴露的协议应该复用该应用采用的暴露协议
            // 比如应用暴露了两个服务，一个用 rest , 一个用 dubbo 协议暴露
            // MetadataService 两者任选其一暴露，可以取第一个，也可以随机。端口需要和原有协议端口一致
            // 这样做的好处可以复用原有协议 server 端的 Io 线程以及线程池资源

            // 比如应用服务全部以 rest 协议暴露，那么 MetadataService 也应该以 rest 协议暴露并且端口一样
            // 如果采用默认的 dubbo 协议暴露的话，又得新建 IO 线程和线程池，不能复用原有 rest 协议相关 IO 资源

            // 我们的场景中，rest 服务全部对接 nginx , 只需要将 rest 服务的 ip:port 添加到 nginx 的 upstream 模块
            // nginx -> rest 服务 -> dubbo 服务，这种场景下其实 rest 服务是不需要到注册中心去注册的，只需要配置在 nginx 中即可(静态方案)

            // 动态方案就需要将 rest 服务注册到注册中心，OpenResty（Nginx + Lua）使用 Lua 脚本动态查询服务注册中心并更新后端节点。(rest 协议也采用的是应用级服务发现)
            // lua 脚本去注册中心查询 rest 应用对应的端点

            // 或者采用 nginx-upsync-module 模块自动到注册中心同步 upstream
            serviceConfig.setProtocol(generateMetadataProtocol()); // 默认为 dubbo 协议，端口号自增
            serviceConfig.setInterface(MetadataService.class);
            serviceConfig.setRef(metadataService); // InMemoryWritableMetadataService
            serviceConfig.setGroup(getApplicationConfig().getName()); // 注意这里的 group 是应用名
            serviceConfig.setVersion(metadataService.version());

            // export
            // MetadataService 发布之后，不会再 ServiceNameMappingListener 中建立 MetadataService 到应用名的映射
            // 其他部分和正常服务发布一样的流程

            // 既然走到了这里，说明开启了应用级服务发现，那么这里的 Registries 也是 service-discovery 协议，同样不会将 MetadataServiceUrl
            // 注册到注册中心上，也是只写入元数据中心 (本地 or 远端就看配置没有配置 MetadataConfig)
            // 如果配置了 MetadataConfig ， 那么 metadata-type 就是 remote,元数据远程上报
            serviceConfig.export();

            if (logger.isInfoEnabled()) {
                logger.info("The MetadataService exports urls : " + serviceConfig.getExportedUrls());
            }

            this.serviceConfig = serviceConfig;

        } else {
            if (logger.isWarnEnabled()) {
                logger.warn("The MetadataService has been exported : " + serviceConfig.getExportedUrls());
            }
        }

        return this;
    }

    @Override
    public ConfigurableMetadataServiceExporter unexport() {
        if (isExported()) {
            serviceConfig.unexport();
        }
        return this;
    }

    @Override
    public List<URL> getExportedURLs() {
        return serviceConfig != null ? serviceConfig.getExportedUrls() : emptyList();
    }

    public boolean isExported() {
        return serviceConfig != null && serviceConfig.isExported();
    }

    private ApplicationConfig getApplicationConfig() {
        return ApplicationModel.getConfigManager().getApplication().get();
    }

    private List<RegistryConfig> getRegistries() {
        return new ArrayList<>(ApplicationModel.getConfigManager().getRegistries());
    }

    private ProtocolConfig generateMetadataProtocol() {
        ProtocolConfig defaultProtocol = new ProtocolConfig();
        defaultProtocol.setName(DUBBO);
        // defaultProtocol.setHost() ?
        // auto-increment port
        defaultProtocol.setPort(-1);
        return defaultProtocol;
    }
}
