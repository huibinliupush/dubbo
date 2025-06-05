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

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ConfigUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Configuration from system properties and dubbo.properties
 */
public class PropertiesConfiguration implements Configuration {

    public PropertiesConfiguration() {
        // OrderedPropertiesProvider 用于向加载之后的 dubbo.properties，添加新的配置，或者覆盖原来的配置
        // 当 dubbo.properties 加载之后，会挨个调用 OrderedPropertiesProvider 的 initProperties 方法
        ExtensionLoader<OrderedPropertiesProvider> propertiesProviderExtensionLoader = ExtensionLoader.getExtensionLoader(OrderedPropertiesProvider.class);
        Set<String> propertiesProviderNames = propertiesProviderExtensionLoader.getSupportedExtensions();
        // 如果工程没有实现 OrderedPropertiesProvider 扩展，那么这里就直接返回
        // 否则 加载 dubbo.properties，并用 OrderedPropertiesProviders 覆盖
        if (propertiesProviderNames == null || propertiesProviderNames.isEmpty()) {
            return;
        }
        List<OrderedPropertiesProvider> orderedPropertiesProviders = new ArrayList<>();
        for (String propertiesProviderName : propertiesProviderNames) {
            orderedPropertiesProviders.add(propertiesProviderExtensionLoader.getExtension(propertiesProviderName));
        }

        //order the propertiesProvider according the priority descending
        // priority 值越大，优先级越高，排在前面
        orderedPropertiesProviders.sort((OrderedPropertiesProvider a, OrderedPropertiesProvider b) -> {
            return b.priority() - a.priority();
        });

        //load the default properties
        // 优先从 -Ddubbo.properties.file 指定的文件路径中加载 dubbo.properties
        // 其次从环境变种中指定的文件路径中加载 dubbo.properties
        // 最后在从 classpath 下加载 dubbo.properties
        // 如果当前工程目录下没有 dubbo.properties，则从工程依赖的各个 jar 包中加载 dubbo.properties 文件
        Properties properties = ConfigUtils.getProperties();

        //override the properties.
        // 利用 OrderedPropertiesProvider 覆盖加载的 properties（dubbo.properties）
        for (OrderedPropertiesProvider orderedPropertiesProvider :
                orderedPropertiesProviders) {
            properties.putAll(orderedPropertiesProvider.initProperties());
        }
        // 缓存到 ConfigUtils 中的 PROPERTIES 字段
        ConfigUtils.setProperties(properties);
    }
    // 先从系统变量中获取，如果没有，再从 PROPERTIES 中获取
    // dubbo.properties 中的 value 支持占位符引用
    @Override
    public Object getInternalProperty(String key) {
        return ConfigUtils.getProperty(key);
    }
}
