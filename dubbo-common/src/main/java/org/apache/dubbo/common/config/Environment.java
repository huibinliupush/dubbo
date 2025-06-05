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
package org.apache.dubbo.common.config;

import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.context.FrameworkExt;
import org.apache.dubbo.common.context.LifecycleAdapter;
import org.apache.dubbo.common.extension.DisableInject;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.context.ConfigConfigurationAdapter;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class Environment extends LifecycleAdapter implements FrameworkExt {
    public static final String NAME = "environment";
    // 封装 dubbo.properties 的配置
    // 优先从 -Ddubbo.properties.file 指定的文件路径中加载 dubbo.properties
    // 其次从环境变种中指定的文件路径中加载 dubbo.properties
    // 最后在从 classpath 下加载 dubbo.properties
    // 如果当前工程目录下没有 dubbo.properties，则从工程依赖的各个 jar 包中加载 dubbo.properties 文件
    private final PropertiesConfiguration propertiesConfiguration;
    private final SystemConfiguration systemConfiguration;
    private final EnvironmentConfiguration environmentConfiguration;
    // 封装配置中心中的全局 dubbo.properties 的配置，配置文件由 configFile 参数指定（全局）
    private final InmemoryConfiguration externalConfiguration;
    // 封装配置中心中的应用级 dubbo.properties 的配置，配置文件由 appConfigFile 参数指定（应用级），默认 dubbo.properties
    // see : org.apache.dubbo.config.ConfigCenterConfig.appConfigFile
    private final InmemoryConfiguration appExternalConfiguration;

    private CompositeConfiguration globalConfiguration;
    // 在配置中心启动之后，会从配置中心获取对应的全局 dubbo.properties ， 应用级 dubbo.properties 分别填充到以下 Map 集合中
    // org.apache.dubbo.config.bootstrap.DubboBootstrap.prepareEnvironment
    private Map<String, String> externalConfigurationMap = new HashMap<>(); // 全局
    // appExternalConfigurationMap 的优先级高于 externalConfigurationMap
    // see : org.apache.dubbo.config.ConfigCenterConfig.appConfigFile
    private Map<String, String> appExternalConfigurationMap = new HashMap<>(); // 应用级 dubbo 配置

    private boolean configCenterFirst = true;
    // 类型为 CompositeDynamicConfiguration ， 用于组织多配置中心中的配置
    // 配置中心中的 remote 配置，封装在 DynamicConfiguration 中
    private DynamicConfiguration dynamicConfiguration;

    public Environment() {
        // 封装 dubbo.properties 的配置
        this.propertiesConfiguration = new PropertiesConfiguration();
        // 封装系统变量 -D 配置
        this.systemConfiguration = new SystemConfiguration();
        // 封装环境变量配置
        this.environmentConfiguration = new EnvironmentConfiguration();
        // 封装配置中心中的 dubbo.properties 的配置（全局）
        this.externalConfiguration = new InmemoryConfiguration();
        // 封装配置中心中的应用级 dubbo.properties 的配置
        this.appExternalConfiguration = new InmemoryConfiguration();
    }

    @Override
    public void initialize() throws IllegalStateException {
        ConfigManager configManager = ApplicationModel.getConfigManager();
        // 每个 configBean 被 spring 初始化之后，都会调用 AbstractConfig.addIntoConfigManager
        // 将自身加入到 configManager 中
        // org.apache.dubbo.config.AbstractConfig.addIntoConfigManager

        // 获取 ConfigCenterConfig 的配置，形如 dubbo.config-center. 相关配置
        // 支持多个配置中心
        Optional<Collection<ConfigCenterConfig>> defaultConfigs = configManager.getDefaultConfigCenter();
        defaultConfigs.ifPresent(configs -> {
            // 此时的配置中心还未启动，ConfigCenterConfig 中存储的相关远程配置集合都还是空的，比如，externalConfiguration，appExternalConfiguration
            // 下面只是将 ConfigCenterConfig 相关配置集合的引用关联到 Environment 的相关集合中
            // 当配置中心启动之后，Environment 中自然也就填充了相关远程配置
            for (ConfigCenterConfig config : configs) {
                // 使得 Environment 中的 externalConfigurationMap 关联到 ConfigCenterConfig 中的 externalConfiguration
                this.setExternalConfigMap(config.getExternalConfiguration());
                // 使得 Environment 中的 appExternalConfigurationMap 关联到 ConfigCenterConfig 中的 appExternalConfiguration
                this.setAppExternalConfigMap(config.getAppExternalConfiguration());
            }
        });
        // 相关配置封装到 InmemoryConfiguration 中
        this.externalConfiguration.setProperties(externalConfigurationMap);
        this.appExternalConfiguration.setProperties(appExternalConfigurationMap);
    }

    @DisableInject
    public void setExternalConfigMap(Map<String, String> externalConfiguration) {
        if (externalConfiguration != null) {
            this.externalConfigurationMap = externalConfiguration;
        }
    }

    @DisableInject
    public void setAppExternalConfigMap(Map<String, String> appExternalConfiguration) {
        if (appExternalConfiguration != null) {
            this.appExternalConfigurationMap = appExternalConfiguration;
        }
    }

    public Map<String, String> getExternalConfigurationMap() {
        return externalConfigurationMap;
    }

    public Map<String, String> getAppExternalConfigurationMap() {
        return appExternalConfigurationMap;
    }

    public void updateExternalConfigurationMap(Map<String, String> externalMap) {
        this.externalConfigurationMap.putAll(externalMap);
    }

    public void updateAppExternalConfigurationMap(Map<String, String> externalMap) {
        this.appExternalConfigurationMap.putAll(externalMap);
    }

    /**
     * At start-up, Dubbo is driven by various configuration, such as Application, Registry, Protocol, etc.
     * All configurations will be converged into a data bus - URL, and then drive the subsequent process.
     * <p>
     * At present, there are many configuration sources, including AbstractConfig (API, XML, annotation), - D, config center, etc.
     * This method helps us to filter out the most priority values from various configuration sources.
     *
     * dubbo 由多种配置源，每种配置源都有特定的优先级，对于某一种具体的配置来说，比如 ConfigCenterConfig, ServiceConfig,ReferenceConfig
     * 它们背后都对应多种配置源，系统变量，环境变量，配置中心，（xml,注解），dubbo.properties
     * 这里是获取 config 背后的所有配置源，并按照优先级组织在 CompositeConfiguration 中
     *
     * 后续 CompositeConfiguration 用来填充对应的 config ， 得到最终的配置（从优先级最高的配置源中获取配置填充
     * ）
     * @param config
     * @return
     */
    public synchronized CompositeConfiguration getPrefixedConfiguration(AbstractConfig config) {
        // Prefix 为对应的 config bean 在 properties 文件中的前缀，通过该前缀可以查找对应的 config bean 的配置属性
        // 比如，ConfigCenterConfig 对应的 prefix 为 ：dubbo.config-center.
        CompositeConfiguration prefixedConfiguration = new CompositeConfiguration(config.getPrefix(), config.getId());
        // 用于将 config bean 转换成 Configuration（底层通过 map 存储相关配置）
        // 通过 config 中的 method,提取相关的配置 —— configMetadata
        // 将 configMetadata 中的配置属性，加上 prefix + id + key 添加到 metaData 中
        Configuration configuration = new ConfigConfigurationAdapter(config);
        // 默认为 true , 是否配置中心的配置优先
        // 按照配置源的优先级组装 CompositeConfiguration
        if (this.isConfigCenterFirst()) {
            // The sequence would be: SystemConfiguration -> AppExternalConfiguration -> ExternalConfiguration -> AbstractConfig -> PropertiesConfiguration
            // Config center has the highest priority
            // 配置源优先级：系统变量 ， 环境变量 ， 配置中心中的 application.properties 配置 ， 配置中心中的 dubbo.properties
            // (xml , 注解) ， 本地 dubbo.properties(可通过 -Ddubbo.properties.file 指定)
            prefixedConfiguration.addConfiguration(systemConfiguration);
            prefixedConfiguration.addConfiguration(environmentConfiguration);
            prefixedConfiguration.addConfiguration(appExternalConfiguration);
            prefixedConfiguration.addConfiguration(externalConfiguration);
            prefixedConfiguration.addConfiguration(configuration);
            prefixedConfiguration.addConfiguration(propertiesConfiguration);
        } else {
            // The sequence would be: SystemConfiguration -> AbstractConfig -> AppExternalConfiguration -> ExternalConfiguration -> PropertiesConfiguration
            // Config center is not first
            // 系统变量 ， 环境变量 ，(xml , 注解),配置中心中的 application.properties 配置 ， 配置中心中的 dubbo.properties, 本地 dubbo.properties
            prefixedConfiguration.addConfiguration(systemConfiguration);
            prefixedConfiguration.addConfiguration(environmentConfiguration);
            prefixedConfiguration.addConfiguration(configuration);
            prefixedConfiguration.addConfiguration(appExternalConfiguration);
            prefixedConfiguration.addConfiguration(externalConfiguration);
            prefixedConfiguration.addConfiguration(propertiesConfiguration);
        }
        return prefixedConfiguration;
    }

    /**
     * There are two ways to get configuration during exposure / reference or at runtime:
     * 1. URL, The value in the URL is relatively fixed. we can get value directly.
     * 2. The configuration exposed in this method is convenient for us to query the latest values from multiple
     * prioritized sources, it also guarantees that configs changed dynamically can take effect on the fly.
     */
    public Configuration getConfiguration() {
        if (globalConfiguration == null) {
            globalConfiguration = new CompositeConfiguration();
            if (dynamicConfiguration != null) {
                globalConfiguration.addConfiguration(dynamicConfiguration);
            }
            globalConfiguration.addConfiguration(systemConfiguration);
            globalConfiguration.addConfiguration(environmentConfiguration);
            globalConfiguration.addConfiguration(appExternalConfiguration);
            globalConfiguration.addConfiguration(externalConfiguration);
            globalConfiguration.addConfiguration(propertiesConfiguration);
        }
        return globalConfiguration;
    }

    public boolean isConfigCenterFirst() {
        return configCenterFirst;
    }

    @DisableInject
    public void setConfigCenterFirst(boolean configCenterFirst) {
        this.configCenterFirst = configCenterFirst;
    }

    public Optional<DynamicConfiguration> getDynamicConfiguration() {
        return Optional.ofNullable(dynamicConfiguration);
    }

    @DisableInject
    public void setDynamicConfiguration(DynamicConfiguration dynamicConfiguration) {
        this.dynamicConfiguration = dynamicConfiguration;
    }

    @Override
    public void destroy() throws IllegalStateException {
        clearExternalConfigs();
        clearAppExternalConfigs();
    }

    public PropertiesConfiguration getPropertiesConfiguration() {
        return propertiesConfiguration;
    }

    public SystemConfiguration getSystemConfiguration() {
        return systemConfiguration;
    }

    public EnvironmentConfiguration getEnvironmentConfiguration() {
        return environmentConfiguration;
    }

    public InmemoryConfiguration getExternalConfiguration() {
        return externalConfiguration;
    }

    public InmemoryConfiguration getAppExternalConfiguration() {
        return appExternalConfiguration;
    }

    // For test
    public void clearExternalConfigs() {
        this.externalConfiguration.clear();
        this.externalConfigurationMap.clear();
    }

    // For test
    public void clearAppExternalConfigs() {
        this.appExternalConfiguration.clear();
        this.appExternalConfigurationMap.clear();
    }
}
