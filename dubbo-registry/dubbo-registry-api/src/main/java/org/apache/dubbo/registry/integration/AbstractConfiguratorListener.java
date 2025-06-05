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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.config.configcenter.ConfigChangeType;
import org.apache.dubbo.common.config.configcenter.ConfigChangedEvent;
import org.apache.dubbo.common.config.configcenter.ConfigurationListener;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.configurator.parser.ConfigParser;
import org.apache.dubbo.rpc.cluster.governance.GovernanceRuleRepository;

import java.util.Collections;
import java.util.List;

/**
 * AbstractConfiguratorListener
 */
public abstract class AbstractConfiguratorListener implements ConfigurationListener {
    private static final Logger logger = LoggerFactory.getLogger(AbstractConfiguratorListener.class);

    //存放从配置中心加载到的"override://" url 转换的configurator（用于根据 override Url 覆盖 providerUrl）
    protected List<Configurator> configurators = Collections.emptyList();

    //服务治理规则存储仓库（其实是对配置中心的一个代理）底层会调用到具体的配置中心 DynamicConfiguration -> ZookeeperDynamicConfiguration
    protected GovernanceRuleRepository ruleRepository = ExtensionLoader.getExtensionLoader(
            GovernanceRuleRepository.class).getDefaultExtension();

    protected final void initWith(String key) {
        //根据key构造配置在配置中心的存储路径
        //cacheListener中缓存 配置存储路径 -> 配置listener,当配置发生变化时 配置中心会通知过来，根据路径 取出Listener执行process配置处理逻辑
        //provider config 配置路径 : /dubbo/config/dubbo/demo-provider.configurators
        //service  config 配置路径 : /dubbo/config/dubbo/org.apache.dubbo.demo.DemoService::.configurators
        ruleRepository.addListener(key, this);
        //从配置中心获取 对应的规则 "override://" URL
        // 从配置中心主动获取配置文件数据，数据在配置中心的体现形式是字节数组，通过 String 编码转换（Yaml格式）
        String rawConfig = ruleRepository.getRule(key, DynamicConfiguration.DEFAULT_GROUP);
        if (!StringUtils.isEmpty(rawConfig)) {
            //将覆盖规则"override://" URL 转换为对应的configurator
            // 将配置中心的 Yaml 格式转换为 "override://" URL，在近一步转换为 configurator
            genConfiguratorsFromRawRule(rawConfig);
        }
    }

    protected final void stopListen(String key) {
        ruleRepository.removeListener(key, this);
    }

    /**
     * 配置发生变更时 配置中心ZookeeperDynamicConfiguration会回调该方法
     * */
    @Override
    public void process(ConfigChangedEvent event) {
        if (logger.isInfoEnabled()) {
            logger.info("Notification of overriding rule, change type is: " + event.getChangeType() +
                    ", raw config content is:\n " + event.getContent());
        }

        if (event.getChangeType().equals(ConfigChangeType.DELETED)) {
            configurators.clear();
        } else {
            // 重新生成 configurators
            if (!genConfiguratorsFromRawRule(event.getContent())) {
                return;
            }
        }
        //回调子类方法 进行具体的配置处理
        notifyOverrides();
    }

    private boolean genConfiguratorsFromRawRule(String rawConfig) {
        boolean parseSuccess = true;
        try {
            // parseConfigurators will recognize app/service config automatically.
            // rawConfig 为配置中心存放的配置文件数据（格式为 Yaml）
            // parseConfigurators 负责通过 rawConfig 加载成 Yaml 文件，然后转换成 "override://" URL
            // 将覆盖规则"override://" URL 转换为对应的configurator
            configurators = Configurator.toConfigurators(ConfigParser.parseConfigurators(rawConfig))
                    .orElse(configurators);
        } catch (Exception e) {
            logger.error("Failed to parse raw dynamic config and it will not take effect, the raw config is: " +
                    rawConfig, e);
            parseSuccess = false;
        }
        return parseSuccess;
    }

    protected abstract void notifyOverrides();

    public List<Configurator> getConfigurators() {
        return configurators;
    }

    public void setConfigurators(List<Configurator> configurators) {
        this.configurators = configurators;
    }
}
