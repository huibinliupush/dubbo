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

import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.remoting.http12.HttpRequest;
import org.apache.dubbo.remoting.http12.HttpResponse;
import org.apache.dubbo.remoting.http12.rest.ParamType;
import org.apache.dubbo.rpc.protocol.tri.rest.Messages;
import org.apache.dubbo.rpc.protocol.tri.rest.RestParameterException;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.NamedValueMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ParameterMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.util.TypeUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public abstract class NamedValueArgumentResolverSupport {
    //
    protected final Map<ParameterMeta, NamedValueMeta> cache = CollectionUtils.newConcurrentHashMap();

    protected final Object resolve(NamedValueMeta meta, HttpRequest request, HttpResponse response) {
        // 获取参数类型（非泛型）,MultiValueMap
        Class<?> type = meta.type();
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            // 负责解析集合类型，有具体的 ArgumentResolver 负责
            return resolveCollectionValue(meta, request, response);
        }
        if (Map.class.isAssignableFrom(type)) {
            // 负责解析 map 类型,MultiValueMap
            return resolveMapValue(meta, request, response);
        }
        Object arg = resolveValue(meta, request, response);
        if (arg != null) {
            return filterValue(arg, meta);
        }
        arg = meta.defaultValue();
        if (arg != null) {
            return arg;
        }
        if (meta.required()) {
            throw new RestParameterException(Messages.ARGUMENT_VALUE_MISSING, meta.name(), type);
        }
        return null;
    }
    // 填充 NamedValueMeta
    protected final NamedValueMeta updateNamedValueMeta(ParameterMeta parameter, NamedValueMeta meta) {
        // 此时的 NamedValueMeta 刚刚通过 org.apache.dubbo.rpc.protocol.tri.rest.argument.AbstractAnnotationBaseArgumentResolver.createNamedValueMeta
        // 从方法参数标注的注解 @RequestParam，@PathVariable，@RequestHeader，@RequestBody 身上刚刚提取出来的 NamedValueMeta
        if (meta.isNameEmpty()) { // 如果参数注解上没有指定具体的 value
            // 那么就采用方法参数定义中的 name
            meta.setName(parameter.getName());
        }
        if (meta.paramType() == null) {
            // 获取参数的类型，比如 如果是 RequstBody 这里就是 ParamType.Body see : org.apache.dubbo.rpc.protocol.tri.rest.support.spring.RequestBodyArgumentResolver.getParamType
            // 如果参数标注的是 @PathVariable，那么这里就是 PathVariable
            meta.setParamType(getParamType(meta));
        }
        // 获取参数类型，比如： List,MultiValueMap
        Class<?> type = parameter.getActualType();
        meta.setType(type);
        // 获取参数的泛型类型：，比如  List<User>, MultiValueMap<String, List<User>>
        meta.setGenericType(parameter.getActualGenericType());
        if (type.isArray()) {
            meta.setNestedTypes(new Class<?>[] {type});
        } else {
            // 获取参数中的泛型类型，比如 List<User> 这里获取到的泛型类型就是 User
            // Map<String,User> 这里获取到的泛型类型就是 [String , User]
            // MultiValueMap<String, List<User>>  这里获取到的泛型类型就是 [String , List(不带 User)]
            meta.setNestedTypes(TypeUtils.getNestedActualTypes(meta.genericType()));
        }
        // ParameterMeta
        meta.setParameter(parameter);
        return meta;
    }

    protected ParamType getParamType(NamedValueMeta meta) {
        return null;
    }

    protected String emptyDefaultValue(NamedValueMeta meta) {
        return meta.defaultValue();
    }

    protected abstract Object resolveValue(NamedValueMeta meta, HttpRequest request, HttpResponse response);

    protected Object filterValue(Object value, NamedValueMeta meta) {
        return value;
    }

    protected Object resolveCollectionValue(NamedValueMeta meta, HttpRequest request, HttpResponse response) {
        return resolveValue(meta, request, response);
    }

    protected Object resolveMapValue(NamedValueMeta meta, HttpRequest request, HttpResponse response) {
        Object value = resolveValue(meta, request, response);
        return value instanceof Map ? value : Collections.singletonMap(meta.name(), value);
    }
}
