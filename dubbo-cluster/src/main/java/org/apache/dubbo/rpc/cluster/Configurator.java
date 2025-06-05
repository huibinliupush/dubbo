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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.dubbo.rpc.cluster.Constants.PRIORITY_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;

/**
 * Configurator. (SPI, Prototype, ThreadSafe)
 *
 */
public interface Configurator extends Comparable<Configurator> {

    /**
     * Get the configurator url.
     *
     * @return configurator url.
     */
    URL getUrl();

    /**
     * Configure the provider url.
     *
     * @param url - old provider url.
     * @return new provider url.
     */
    URL configure(URL url);


    /**
     * Convert override urls to map for use when re-refer. Send all rules every time, the urls will be reassembled and
     * calculated
     *
     * URL contract:
     * <ol>
     * <li>override://0.0.0.0/...( or override://ip:port...?anyhost=true)&para1=value1... means global rules
     * (all of the providers take effect)</li>
     * <li>override://ip:port...?anyhost=false Special rules (only for a certain provider)</li>
     * <li>override:// rule is not supported... ,needs to be calculated by registry itself</li>
     * <li>override://0.0.0.0/ without parameters means clearing the override</li>
     * </ol>
     *
     * override://addr/interface?group=x&versison=y&category=dynamicconfigurators&side=side&parametersKey=parametersValue&providerAddresses=多个provideraddr逗号分隔&category=dynamicconfigurators&configVersion=&application=&enabled=
     * 应用级动态配置：&category=appdynamicconfigurators  针对哪些地址，哪些服务
     * 服务级动态配置：&category=dynamicconfigurators  针对哪些地址，哪些应用
     *
     * 不管什么级别的配置最终都会生成多个 override:// url
     *
     * see : org.apache.dubbo.rpc.cluster.configurator.parser.ConfigParser#parseConfigurators(java.lang.String)
     *
     * 这里会根据生成的多个  override:// url 生成对应的 Configurator
     * @param urls URL list to convert
     * @return converted configurator list
     */
    static Optional<List<Configurator>> toConfigurators(List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return Optional.empty();
        }
        // 根据协议 override:// 获取对应的 Configurator 实现 OverrideConfigurator
        ConfiguratorFactory configuratorFactory = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .getAdaptiveExtension();

        List<Configurator> configurators = new ArrayList<>(urls.size());
        for (URL url : urls) {
            // 如果有一个是 empty 协议，则清空所有 configurators
            if (EMPTY_PROTOCOL.equals(url.getProtocol())) {
                configurators.clear();
                break;
            }
            // 动态配置文件中的 Parameters
            Map<String, String> override = new HashMap<>(url.getParameters());
            //The anyhost parameter of override may be added automatically, it can't change the judgement of changing url
            override.remove(ANYHOST_KEY);
            if (override.size() == 0) {
                configurators.clear();
                continue;
            }
            // 生成 OverrideConfigurator
            configurators.add(configuratorFactory.getConfigurator(url));
        }
        Collections.sort(configurators);
        return Optional.of(configurators);
    }

    /**
     * Sort by host, then by priority
     * 1. the url with a specific host ip should have higher priority than 0.0.0.0
     * 2. if two url has the same host, compare by priority value；
     */
    @Override
    default int compareTo(Configurator o) {
        if (o == null) {
            return -1;
        }

        int ipCompare = getUrl().getHost().compareTo(o.getUrl().getHost());
        // host is the same, sort by priority
        if (ipCompare == 0) {
            int i = getUrl().getParameter(PRIORITY_KEY, 0);
            int j = o.getUrl().getParameter(PRIORITY_KEY, 0);
            return Integer.compare(i, j);
        } else {
            return ipCompare;
        }
    }
}
