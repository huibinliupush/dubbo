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

import org.apache.dubbo.common.utils.MethodUtils;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.model.MethodDescriptor.RpcType;
import org.apache.dubbo.rpc.protocol.tri.rest.util.RestToolKit;
import org.apache.dubbo.rpc.protocol.tri.rest.util.TypeUtils;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public final class MethodMeta extends AnnotationSupport {
    // methods 为 service 继承关系中所有类中对应的同一 method
    // 比如：接口定义了一个 method , 实现类实现了这个 method
    // 那么这里就是两个 method, 因为 mvc 注解可以标注在继承关系中的任意一个地方
    // 所以需要全量查找
    private final List<Method> hierarchy;
    private final Method method;
    private MethodDescriptor methodDescriptor;
    // 封装方法参数相关的元信息 MethodParameterMeta，包括继承体系中所有的 Parameter ， 参数名称
    private ParameterMeta[] parameters;
    private ParameterMeta returnParameter;
    private final ServiceMeta serviceMeta;

    public MethodMeta(List<Method> hierarchy, MethodDescriptor methodDescriptor, ServiceMeta serviceMeta) {
        super(serviceMeta.getToolKit());
        this.hierarchy = hierarchy;
        method = initMethod(hierarchy, methodDescriptor);
        this.methodDescriptor = methodDescriptor;
        this.serviceMeta = serviceMeta;
    }

    private Method initMethod(List<Method> hierarchy, MethodDescriptor methodDescriptor) {
        Method method = null;
        if (methodDescriptor != null) {
            method = methodDescriptor.getMethod();
        }
        return method == null ? hierarchy.get(hierarchy.size() - 1) : method;
    }

    public void initParameters() {
        RpcType rpcType = methodDescriptor.getRpcType();
        if (rpcType == RpcType.CLIENT_STREAM || rpcType == RpcType.BI_STREAM) {
            Type genericType = TypeUtils.getNestedGenericType(method.getGenericReturnType(), 0);
            parameters = new ParameterMeta[] {new StreamParameterMeta(getToolKit(), genericType, method, hierarchy)};
            return;
        }
        // rest 映射方法的参数个数
        int count = rpcType == RpcType.SERVER_STREAM ? 1 : method.getParameterCount();
        // parameterHierarchies 第一维 parameterHierarchies[0] 存放的是参数 1 相关的 Parameters(serviceImpl ， 父类 ， 接口)
        // 一个参数会对应多个 Parameter ， 因为 rest 映射方法 method 本身就对应多个，分别是来自 serviceImpl ， 父类 ， 接口
        // 针对同一个映射方法，在继承关系体系中会对应多个 method , 那么 method 中的某个 Parameter 自然也对应多个
        // 同理 parameterHierarchies[n] 对应存储的也就是 参数 n 对应的 Parameters(serviceImpl ， 父类 ， 接口)
        List<List<Parameter>> parameterHierarchies = new ArrayList<>(count);
        // hierarchy 有多少个，那么对应的 List<Parameter> 就有多少个
        // 方法参数有多少个，那么 parameterHierarchies 的 size 就有多少个
        for (int i = 0, size = hierarchy.size(); i < size; i++) {
            Method m = hierarchy.get(i);
            Parameter[] mps = m.getParameters();
            for (int j = 0; j < count; j++) {
                List<Parameter> parameterHierarchy;
                if (parameterHierarchies.size() <= j) {
                    parameterHierarchy = new ArrayList<>(size);
                    parameterHierarchies.add(parameterHierarchy);
                } else {
                    parameterHierarchy = parameterHierarchies.get(j);
                }
                parameterHierarchy.add(mps[j]);
            }
        }
        // 读取方法参数名称，后续在处理 rest 请求的时候会使用该方法名称作为 key
        // 根据参数标注的注解 @RequestParam ， @PathVariable，@RequestHeader
        // 到 http 请求体中对应的 QueryParam , Path ,Header 中去查找（查找方法调用参数值）
        // 然后封装成 RpcInvocation 调用 Invoker
        String[] parameterNames = getToolKit().getParameterNames(method);
        ParameterMeta[] parameters = new ParameterMeta[count];
        for (int i = 0; i < count; i++) {
            String parameterName = parameterNames == null ? null : parameterNames[i];
            // 封装方法参数相关的元信息 MethodParameterMeta，包括继承体系中所有的 Parameter ， 参数名称, 参数index
            parameters[i] = new MethodParameterMeta(parameterHierarchies.get(i), parameterName, i, this);
        }
        this.parameters = parameters;
    }

    public List<Method> getHierarchy() {
        return hierarchy;
    }

    public Method getMethod() {
        return method;
    }

    public MethodDescriptor getMethodDescriptor() {
        return methodDescriptor;
    }

    public void setMethodDescriptor(MethodDescriptor methodDescriptor) {
        this.methodDescriptor = methodDescriptor;
    }

    public ParameterMeta[] getParameters() {
        return parameters;
    }

    public ParameterMeta getReturnParameter() {
        ParameterMeta returnParameter = this.returnParameter;
        if (returnParameter == null) {
            this.returnParameter = returnParameter = new ReturnParameterMeta(getToolKit(), hierarchy, method);
        }
        return returnParameter;
    }

    public ServiceMeta getServiceMeta() {
        return serviceMeta;
    }

    public Class<?> getReturnType() {
        return method.getReturnType();
    }

    public Class<?> getActualReturnType() {
        return getReturnParameter().getActualType();
    }

    public Type getGenericReturnType() {
        return method.getGenericReturnType();
    }

    public Type getActualGenericReturnType() {
        return getReturnParameter().getActualGenericType();
    }

    @Override
    public List<? extends AnnotatedElement> getAnnotatedElements() {
        return hierarchy;
    }

    @Override
    protected AnnotatedElement getAnnotatedElement() {
        return method;
    }

    @Override
    public int hashCode() {
        return method.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || obj.getClass() != MethodMeta.class) {
            return false;
        }
        return method.equals(((MethodMeta) obj).method);
    }

    @Override
    public String toString() {
        return "MethodMeta{method=" + toShortString() + ", service=" + serviceMeta.toShortString() + '}';
    }

    public String toShortString() {
        return MethodUtils.toShortString(method);
    }

    public static final class StreamParameterMeta extends ParameterMeta {

        private final Class<?> type;
        private final Type genericType;
        private final AnnotatedElement element;
        private final List<? extends AnnotatedElement> elements;

        StreamParameterMeta(
                RestToolKit toolKit,
                Type genericType,
                AnnotatedElement element,
                List<? extends AnnotatedElement> elements) {
            super(toolKit, "value");
            type = TypeUtils.getActualType(genericType);
            this.genericType = genericType;
            this.element = element;
            this.elements = elements;
        }

        @Override
        public String getDescription() {
            return "Stream parameter [" + element + "]";
        }

        @Override
        public Class<?> getType() {
            return type;
        }

        @Override
        public Type getGenericType() {
            return genericType;
        }

        @Override
        protected AnnotatedElement getAnnotatedElement() {
            return element;
        }

        @Override
        public List<? extends AnnotatedElement> getAnnotatedElements() {
            return elements;
        }
    }

    public static final class ReturnParameterMeta extends ParameterMeta {

        private final List<Method> hierarchy;
        private final Method method;

        ReturnParameterMeta(RestToolKit toolKit, List<Method> hierarchy, Method method) {
            super(toolKit, null);
            this.hierarchy = hierarchy;
            this.method = method;
        }

        public Method getMethod() {
            return method;
        }

        @Override
        public Class<?> getType() {
            return method.getReturnType();
        }

        @Override
        public Type getGenericType() {
            return method.getGenericReturnType();
        }

        @Override
        public List<? extends AnnotatedElement> getAnnotatedElements() {
            return hierarchy;
        }

        @Override
        protected AnnotatedElement getAnnotatedElement() {
            return method;
        }
    }
}
