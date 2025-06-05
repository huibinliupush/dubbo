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
package org.apache.dubbo.config.context;

import org.apache.dubbo.common.config.Configuration;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.AbstractConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * This class receives an {@link AbstractConfig} and exposes its attributes through {@link Configuration}
 *
 * 用于将 config bean 转换成 Configuration（底层通过 map 存储相关配置）
 */
public class ConfigConfigurationAdapter implements Configuration {

    private Map<String, String> metaData;

    public ConfigConfigurationAdapter(AbstractConfig config) {
        // 通过 config 中的 method,提取相关的配置 —— configMetadata
        Map<String, String> configMetadata = config.getMetaData();
        // 将 config 中的配置属性，加上 prefix + id + key 添加到 metaData 中
        metaData = new HashMap<>(configMetadata.size());
        for (Map.Entry<String, String> entry : configMetadata.entrySet()) {
            String prefix = config.getPrefix().endsWith(".") ? config.getPrefix() : config.getPrefix() + ".";
            String id = StringUtils.isEmpty(config.getId()) ? "" : config.getId() + ".";
            metaData.put(prefix + id + entry.getKey(), entry.getValue());
        }
    }

    @Override
    public Object getInternalProperty(String key) {
        return metaData.get(key);
    }

}
