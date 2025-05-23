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
package org.apache.dubbo.metadata.definition;

import org.apache.dubbo.metadata.definition.model.FullServiceDefinition;
import org.apache.dubbo.metadata.definition.model.MethodDefinition;
import org.apache.dubbo.metadata.definition.model.ServiceDefinition;
import org.apache.dubbo.metadata.definition.model.TypeDefinition;
import org.apache.dubbo.metadata.definition.util.ClassUtils;

import com.google.gson.Gson;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * 2015/1/27.
 */
public final class ServiceDefinitionBuilder {

    /**
     * Describe a Java interface in {@link ServiceDefinition}.
     *
     * @return Service description
     */
    public static ServiceDefinition build(final Class<?> interfaceClass) {
        ServiceDefinition sd = new ServiceDefinition();
        build(sd, interfaceClass);
        return sd;
    }

    public static FullServiceDefinition buildFullDefinition(final Class<?> interfaceClass) {
        FullServiceDefinition sd = new FullServiceDefinition();
        build(sd, interfaceClass);
        return sd;
    }

    public static FullServiceDefinition buildFullDefinition(final Class<?> interfaceClass, Map<String, String> params) {
        FullServiceDefinition sd = new FullServiceDefinition();
        build(sd, interfaceClass);
        sd.setParameters(params);
        return sd;
    }

    public static <T extends ServiceDefinition> void build(T sd, final Class<?> interfaceClass) {
        // org.apache.dubbo.demo.DemoService
        sd.setCanonicalName(interfaceClass.getCanonicalName());
        // 类文件所在的 target 位置
        sd.setCodeSource(ClassUtils.getCodeSource(interfaceClass));

        TypeDefinitionBuilder builder = new TypeDefinitionBuilder();
        // 获取接口 DemoService 所有的 public 实例方法
        List<Method> methods = ClassUtils.getPublicNonStaticMethods(interfaceClass);
        for (Method method : methods) {
            // 挨个构建方法对应的 MethodDefinition
            MethodDefinition md = new MethodDefinition();
            md.setName(method.getName());

            // Process parameter types.
            // 参数类型的 class
            Class<?>[] paramTypes = method.getParameterTypes();
            // 参数元类型 Type, see : https://chat.deepseek.com/a/chat/s/db7341a1-171e-4608-b276-8989e32ee6bf
            Type[] genericParamTypes = method.getGenericParameterTypes();

            String[] parameterTypes = new String[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                // 构建参数类型对应的 TypeDefinition
                // 其实 TypeDefinition 中主要设置的就是类型名 type
                // 然后顺带缓存构建该类型中包含的所有类型 TypeDefinition
                // 比如，array , list 中的泛型真实类型， map 中的 key ,value 类型
                // class 中所有的 field 类型
                TypeDefinition td = builder.build(genericParamTypes[i], paramTypes[i]);
                parameterTypes[i] = td.getType();
            }
            // 方法参数类型
            md.setParameterTypes(parameterTypes);

            // Process return type.
            TypeDefinition td = builder.build(method.getGenericReturnType(), method.getReturnType());
            md.setReturnType(td.getType());

            sd.getMethods().add(md);
        }
        // service 接口涉及到的所有类型 TypeDefinitions
        sd.setTypes(builder.getTypeDefinitions());
    }

    /**
     * Describe a Java interface in Json schema.
     *
     * @return Service description
     */
    public static String schema(final Class<?> clazz) {
        ServiceDefinition sd = build(clazz);
        Gson gson = new Gson();
        return gson.toJson(sd);
    }

    private ServiceDefinitionBuilder() {
    }
}


