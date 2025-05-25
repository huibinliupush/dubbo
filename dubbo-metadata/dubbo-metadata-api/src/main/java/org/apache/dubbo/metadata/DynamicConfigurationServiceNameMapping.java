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
package org.apache.dubbo.metadata;

import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static java.lang.String.valueOf;
import static java.util.Arrays.asList;
import static org.apache.dubbo.common.utils.StringUtils.SLASH;
import static org.apache.dubbo.rpc.model.ApplicationModel.getName;

/**
 * The {@link ServiceNameMapping} implementation based on {@link DynamicConfiguration}
 */
public class DynamicConfigurationServiceNameMapping implements ServiceNameMapping {

    public static String DEFAULT_MAPPING_GROUP = "mapping";

    private static final List<String> IGNORED_SERVICE_INTERFACES = asList(MetadataService.class.getName());

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public void map(String serviceInterface, String group, String version, String protocol) {
        // MetadataService 元数据服务不会进行映射
        if (IGNORED_SERVICE_INTERFACES.contains(serviceInterface)) {
            return;
        }
        // ZookeeperDynamicConfigurationFactory or NacosDynamicConfigurationFactory
        DynamicConfiguration dynamicConfiguration = DynamicConfiguration.getDynamicConfiguration();

        // the Dubbo Service Key as group
        // the service(application) name as key
        // It does matter whatever the content is, we just need a record

        // applicationName
        String key = getName();
        // 时间戳
        String content = valueOf(System.currentTimeMillis());
        execute(() -> {
            // buildGroup : （/dubbo/config/）mapping/org.apache.dubbo.demo.DemoService
            // key : service-discovery-provider(应用名)
            // content : 时间戳
            // 如果配置中是 zooKeeper 的话这里会创建持久节点, nacos 的话则是 key 为 dataid , buildGroup 为  group
            dynamicConfiguration.publishConfig(key, buildGroup(serviceInterface, group, version, protocol), content);
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Dubbo service[%s] mapped to interface name[%s].",
                        group, serviceInterface, group));
            }
        });
    }

    @Override
    public Set<String> get(String serviceInterface, String group, String version, String protocol) {

        DynamicConfiguration dynamicConfiguration = DynamicConfiguration.getDynamicConfiguration();

        Set<String> serviceNames = new LinkedHashSet<>();
        execute(() -> {
            // buildGroup : （/dubbo/config/）mapping/org.apache.dubbo.demo.DemoService
            // 如果是 zooKeeper 配置中心，则从 /dubbo/config/mapping/org.apache.dubbo.demo.DemoService/  路径下获取应用名
            // 如果是 nacos 配置中心， 则通过 buildGroup 来查找 dataId (应用名)
            Set<String> keys = dynamicConfiguration.getConfigKeys(buildGroup(serviceInterface, group, version, protocol));
            serviceNames.addAll(keys);
        });
        return Collections.unmodifiableSet(serviceNames);
    }

    protected static String buildGroup(String serviceInterface, String group, String version, String protocol) {
        //        the issue : https://github.com/apache/dubbo/issues/4671
        //        StringBuilder groupBuilder = new StringBuilder(serviceInterface)
        //                .append(KEY_SEPARATOR).append(defaultString(group))
        //                .append(KEY_SEPARATOR).append(defaultString(version))
        //                .append(KEY_SEPARATOR).append(defaultString(protocol));
        //        return groupBuilder.toString();
        // mapping/org.apache.dubbo.demo.DemoService
        return DEFAULT_MAPPING_GROUP + SLASH + serviceInterface;
    }

    private void execute(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable e) {
            if (logger.isWarnEnabled()) {
                logger.warn(e.getMessage(), e);
            }
        }
    }
}
