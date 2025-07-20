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
package org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta;

import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.http12.rest.ParamType;
import org.apache.dubbo.rpc.protocol.tri.rest.Messages;
import org.apache.dubbo.rpc.protocol.tri.rest.RestException;

import java.lang.reflect.Type;
import java.util.Arrays;

/**
 * 由 createNamedValueMeta 方法进行创建，根据参数中标注的注解 @RequestParam，@PathVariable，@RequestHeader，@RequestBody 身上刚刚提取出来的 NamedValueMeta
 * org.apache.dubbo.rpc.protocol.tri.rest.argument.AbstractAnnotationBaseArgumentResolver#createNamedValueMeta(org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ParameterMeta, org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.AnnotationMeta)
 *
 * updateNamedValueMeta 负责对 NamedValueMeta 其他属性进行填充
 * org.apache.dubbo.rpc.protocol.tri.rest.argument.NamedValueArgumentResolverSupport#updateNamedValueMeta(org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ParameterMeta, org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.NamedValueMeta)
 * */
public class NamedValueMeta {

    public static final NamedValueMeta EMPTY = new NamedValueMeta();

    private String name;
    private final boolean required;
    private final String defaultValue;
    // 获取参数的类型，比如 如果是 RequstBody 这里就是 ParamType.Body see : org.apache.dubbo.rpc.protocol.tri.rest.support.spring.RequestBodyArgumentResolver.getParamType
    // 如果参数标注的是 @PathVariable，那么这里就是 PathVariable
    private ParamType paramType;
    // 获取参数类型，比如： List
    private Class<?> type;
    // 获取参数的泛型类型：，比如  List<User>
    private Type genericType;
    // 获取参数中的泛型类型，比如 List<User> 这里获取到的泛型类型就是 User
    // Map<String,User> 这里获取到的泛型类型就是 [String , User]
    // MultiValueMap<String, List<User>>  这里获取到的泛型类型就是 [String , List(不带 User)]
    private Class<?>[] nestedTypes;
    // 映射的方法参数元数据
    private ParameterMeta parameter;

    public NamedValueMeta(String name, boolean required, String defaultValue) {
        // 方法参数名称，如果 @RequestParam，@PathVariable，@RequestHeader，@RequestBody 等注解中没有明确指定 value(参数名称)
        // 那么就采用方法中定义的参数名称，后续会用这个名称从 rest request 中提取参数值
        this.name = name;
        // 注解中的 required 属性（是否为必填参数）
        this.required = required;
        this.defaultValue = defaultValue;
    }

    public NamedValueMeta(String name, boolean required) {
        this.name = name;
        this.required = required;
        defaultValue = null;
    }

    public NamedValueMeta() {
        required = false;
        defaultValue = null;
    }

    public String name() {
        if (name == null) {
            throw new RestException(Messages.ARGUMENT_NAME_MISSING, type);
        }
        return name;
    }

    public NamedValueMeta setName(String name) {
        this.name = name;
        return this;
    }

    public boolean isNameEmpty() {
        return StringUtils.isEmpty(name);
    }

    public boolean required() {
        return required;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public ParamType paramType() {
        return paramType;
    }

    public NamedValueMeta setParamType(ParamType paramType) {
        this.paramType = paramType;
        return this;
    }

    public Class<?> type() {
        return type;
    }

    public NamedValueMeta setType(Class<?> type) {
        this.type = type;
        return this;
    }

    public Type genericType() {
        return genericType;
    }

    public NamedValueMeta setGenericType(Type genericType) {
        this.genericType = genericType;
        return this;
    }

    public Class<?>[] nestedTypes() {
        return nestedTypes;
    }

    public NamedValueMeta setNestedTypes(Class<?>[] nestedTypes) {
        this.nestedTypes = nestedTypes;
        return this;
    }

    public Class<?> nestedType() {
        return nestedTypes == null ? null : nestedTypes[0];
    }

    public Class<?> nestedType(int index) {
        return nestedTypes == null || nestedTypes.length <= index ? null : nestedTypes[index];
    }

    public ParameterMeta parameter() {
        return parameter;
    }

    public NamedValueMeta setParameter(ParameterMeta parameter) {
        this.parameter = parameter;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("NamedValueMeta{name='");
        sb.append(name).append('\'');
        if (required) {
            sb.append(", required=true");
        }
        if (defaultValue != null) {
            sb.append(", defaultValue='").append(defaultValue).append('\'');
        }
        if (paramType != null) {
            sb.append(", paramType=").append(paramType);
        }
        if (type != null) {
            sb.append(", type=").append(type);
            if (genericType != type) {
                sb.append(", genericType=").append(genericType);
            }
        }
        if (nestedTypes != null) {
            sb.append(", nestedTypes=").append(Arrays.toString(nestedTypes));
        }
        sb.append('}');
        return sb.toString();
    }
}
