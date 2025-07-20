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

import org.apache.dubbo.remoting.http12.HttpRequest;
import org.apache.dubbo.remoting.http12.HttpResponse;
import org.apache.dubbo.remoting.http12.rest.ParamType;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.protocol.tri.rest.Messages;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.AnnotationMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.NamedValueMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ParameterMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class CompositeArgumentResolver implements ArgumentResolver {
    // CompositeArgumentResolver 封装各种 spring mvc 注解的 Resolver，比如，@RequestParam，@PathVariable，@RequestBody，@RequestHeade
    // 用来指示从 http request 的哪个地方获取请求参数
    private final Map<Class, AnnotationBaseArgumentResolver> resolverMap = new HashMap<>();
    private final ArgumentResolver[] resolvers;
    // CompositeArgumentConverter
    private final ArgumentConverter argumentConverter;

    public CompositeArgumentResolver(FrameworkModel frameworkModel) {
        List<ArgumentResolver> extensions = frameworkModel.getActivateExtensions(ArgumentResolver.class);
        List<ArgumentResolver> resolvers = new ArrayList<>(extensions.size());
        for (ArgumentResolver resolver : extensions) {
            if (resolver instanceof AnnotationBaseArgumentResolver) {
                AnnotationBaseArgumentResolver aar = (AnnotationBaseArgumentResolver) resolver;
                resolverMap.put(aar.accept(), aar);
            } else {
                resolvers.add(resolver);
            }
        }
        this.resolvers = resolvers.toArray(new ArgumentResolver[0]);
        argumentConverter = new CompositeArgumentConverter(frameworkModel);
    }

    public ArgumentConverter getArgumentConverter() {
        return argumentConverter;
    }

    @Override
    public boolean accept(ParameterMeta parameter) {
        return true;
    }

    /**
     *  CompositeArgumentResolver 封装各种 spring mvc 注解的 Resolver，比如，@RequestParam，@PathVariable，@RequestBody，@RequestHeader
     *  用来指示从 http request 的哪个地方获取请求参数
     *  根据方法参数上标注的 spring mvc 注解，解析方法参数（不同的注解不同的解析方式）
     * */
    @Override
    public Object resolve(ParameterMeta parameter, HttpRequest request, HttpResponse response) {
        // 获取方法参数 parameter 上标注的所有注解
        // AnnotationMeta 封装具体标注注解的 element（method or Parameter）, 具体的 mvc 注解，rest toolKit
        for (AnnotationMeta annotation : parameter.findAnnotations()) {
            // 根据 spring mvc 注解获取对应的 AnnotationBaseArgumentResolver
            // @RequestParam 对应 RequestParamArgumentResolver
            // @PathVariable 对应 PathVariableArgumentResolver
            // @RequestBody 对应 RequestBodyArgumentResolver
            // @RequestHeader 对应 RequestHeaderArgumentResolver
            AnnotationBaseArgumentResolver resolver = resolverMap.get(annotation.getAnnotationType());
            if (resolver != null) {
                // 根据参数标注的注解从 rest request 中提取参数值
                // 比如 @RequestBody 参数：将 body 中的 json 反序列化为 hashMap<String, List<User>> 对象
                // 注意这里反序列化出来的是 hashmap 并不是参数类型 interface org.springframework.util.MultiValueMap
                // 所以需要下面的 argumentConverter 将 hashmap 在转换成 MultiValueMap
                Object value = resolver.resolve(parameter, annotation, request, response);
                // CompositeArgumentConverter
                // GeneralTypeConverter 检查 value 是否为指定的 GenericType
                // 比如 List<User> ,那么检查 value 中的每一个元素是否为 User 类型
                return argumentConverter.convert(value, parameter);
            }
        }

        for (ArgumentResolver resolver : resolvers) {
            if (resolver.accept(parameter)) {
                Object value = resolver.resolve(parameter, request, response);
                return argumentConverter.convert(value, parameter);
            }
        }

        throw new IllegalStateException(Messages.ARGUMENT_COULD_NOT_RESOLVED.format(parameter.getDescription()));
    }

    public NamedValueMeta getNamedValueMeta(ParameterMeta parameter) {
        for (AnnotationMeta annotation : parameter.findAnnotations()) {
            AnnotationBaseArgumentResolver resolver = resolverMap.get(annotation.getAnnotationType());
            if (resolver != null) {
                return resolver.getNamedValueMeta(parameter, annotation);
            }
        }

        for (ArgumentResolver resolver : resolvers) {
            if (resolver.accept(parameter)) {
                if (resolver instanceof AbstractArgumentResolver) {
                    return ((AbstractArgumentResolver) resolver).getNamedValueMeta(parameter);
                } else {
                    return new NamedValueMeta().setParamType(ParamType.Attribute);
                }
            }
        }

        return null;
    }
}
