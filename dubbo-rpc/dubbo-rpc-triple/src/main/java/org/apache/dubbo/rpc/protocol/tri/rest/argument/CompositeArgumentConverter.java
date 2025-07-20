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
package org.apache.dubbo.rpc.protocol.tri.rest.argument;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.Pair;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ParameterMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.TypeParameterMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.util.TypeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class CompositeArgumentConverter implements ArgumentConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompositeArgumentConverter.class);

    private final List<ArgumentConverter> converters;
    private final Map<Pair<Class, Class>, List<ArgumentConverter>> cache = CollectionUtils.newConcurrentHashMap();

    public CompositeArgumentConverter(FrameworkModel frameworkModel) {
        converters = frameworkModel.getActivateExtensions(ArgumentConverter.class);
    }

    @Override
    public Object convert(Object value, ParameterMeta parameter) {
        // 获取参数类型（非泛型），interface org.springframework.util.MultiValueMap
        Class<?> type = parameter.getType();
        if (value == null) {
            return TypeUtils.nullDefault(type);
        }
        // value 类型是否正确
        // 对于 map 类型的参数来说，value 序列化出来的是 hashmap 类型，并不是参数指定的 MultiValueMap
        if (type.isInstance(value)) {
            // 如果参数类型不是泛型，直接返回
            if (parameter.getGenericType() instanceof Class) {
                return value;
            }
            // GeneralTypeConverter 检查 value 是否为指定的 GenericType
            // 比如 List<User> ,那么检查 value 中的每一个元素是否为 User 类型
            return parameter.getToolKit().convert(value, parameter);
        }
        // value.getClass() : class java.util.HashMap
        // type : org.springframework.util.MultiValueMap
        // 这里需要将 value 转换成 MultiValueMap 类型
        List<ArgumentConverter> converters = getSuitableConverters(value.getClass(), type);
        Object target;
        for (int i = 0, size = converters.size(); i < size; i++) {
            target = converters.get(i).convert(value, parameter);
            if (target != null) {
                return target;
            }
        }
        // 找不到合适的 ArgumentConverter , 就在这里转换
        return parameter.getToolKit().convert(value, parameter);
    }

    public Object convert(Object value, Class<?> type) {
        if (value == null) {
            return null;
        }

        if (type.isInstance(value)) {
            return value;
        }

        TypeParameterMeta parameter = new TypeParameterMeta(type);
        // MultiValueMapCreator
        List<ArgumentConverter> converters = getSuitableConverters(value.getClass(), type);
        Object target;
        for (int i = 0, size = converters.size(); i < size; i++) {
            target = converters.get(i).convert(value, parameter);
            if (target != null) {
                return target;
            }
        }

        return null;
    }
    // value.getClass() : class java.util.HashMap
    // type : org.springframework.util.MultiValueMap
    private List<ArgumentConverter> getSuitableConverters(Class sourceType, Class targetType) {
        return cache.computeIfAbsent(Pair.of(sourceType, targetType), k -> {
            List<ArgumentConverter> result = new ArrayList<>();
            // MultiValueMapCreator
            for (ArgumentConverter converter : converters) {
                // 获取 MultiValueMapCreator 继承关系中第一个泛型类型 Integer
                Class<?> supportSourceType = TypeUtils.getSuperGenericType(converter.getClass(), 0);
                if (supportSourceType == null) {
                    continue;
                }
                // 获取 MultiValueMapCreator 继承关系中第一个泛型类型 MultiValueMap
                Class<?> supportTargetType = TypeUtils.getSuperGenericType(converter.getClass(), 1);
                if (supportTargetType == null) {
                    continue;
                }
                // see : org.apache.dubbo.rpc.protocol.tri.rest.argument.ArgumentConverter.convert
                // supportSourceType 表示支持转换的源类型，supportTargetType 表示转换之后的目的类型
                if (supportSourceType.isAssignableFrom(sourceType) && targetType.isAssignableFrom(supportTargetType)) {
                    result.add(converter);
                }
            }
            if (result.isEmpty()) {
                // 找不到合适的 ArgumentConverter 就返回空
                return Collections.emptyList();
            }
            LOGGER.info("Found suitable ArgumentConverter for [{}], converters: {}", sourceType, result);
            return result;
        });
    }
}
