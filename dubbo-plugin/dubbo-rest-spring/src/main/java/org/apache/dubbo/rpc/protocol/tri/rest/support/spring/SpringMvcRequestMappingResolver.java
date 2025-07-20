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
package org.apache.dubbo.rpc.protocol.tri.rest.support.spring;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.nested.RestConfig;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.protocol.tri.rest.cors.CorsUtils;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.RequestMapping;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.RequestMapping.Builder;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.RequestMappingResolver;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.AnnotationMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.CorsMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.MethodMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ServiceMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.util.RestToolKit;

import org.springframework.http.HttpStatus;

@Activate(onClass = "org.springframework.web.bind.annotation.RequestMapping")
public class SpringMvcRequestMappingResolver implements RequestMappingResolver {

    private final RestToolKit toolKit;
    private RestConfig restConfig;
    private CorsMeta globalCorsMeta;

    public SpringMvcRequestMappingResolver(FrameworkModel frameworkModel) {
        toolKit = new SpringRestToolKit(frameworkModel);
    }

    @Override
    public void setRestConfig(RestConfig restConfig) {
        this.restConfig = restConfig;
    }

    @Override
    public RestToolKit getRestToolKit() {
        return toolKit;
    }

    @Override
    public RequestMapping resolve(ServiceMeta serviceMeta) {
        // 从 service 的继承关系中查找被 @RequestMapping 注解标注的类
        // 这里也包括 @RequestMapping 的派生注解 @GetMapping 以及 @PostMapping ,都可以标注在类中或者方法中
        // AnnotationMeta 中包含了 @RequestMapping 注解，以及标注的 class , restTool
        AnnotationMeta<?> requestMapping = serviceMeta.findMergedAnnotation(Annotations.RequestMapping);
        AnnotationMeta<?> httpExchange = serviceMeta.findMergedAnnotation(Annotations.HttpExchange);
        if (requestMapping == null && httpExchange == null) {
            return null;
        }
        // 当从 AnnotationMeta 中获取注解属性的时候，会触发，@RequestMapping ， @GetMapping，@PostMapping 的属性合并
        // see : org.apache.dubbo.rpc.protocol.tri.rest.support.spring.SpringRestToolKit.getAttributes

        // 获取 mvc 注解标注的 rest method
        String[] methods = requestMapping == null
                ? httpExchange.getStringArray("method")
                : requestMapping.getStringArray("method");
        // 获取 mvc 注解标注的 rest path
        String[] paths = requestMapping == null ? httpExchange.getValueArray() : requestMapping.getValueArray();
        return builder(requestMapping, httpExchange, serviceMeta.findMergedAnnotation(Annotations.ResponseStatus))
                .method(methods)
                .name(serviceMeta.getType().getSimpleName())
                .path(paths)
                .contextPath(serviceMeta.getContextPath())
                .cors(buildCorsMeta(serviceMeta.findMergedAnnotation(Annotations.CrossOrigin), methods))
                .build(); // 创建初始化各种 Condition （class级）
    }

    @Override
    public RequestMapping resolve(MethodMeta methodMeta) {
        AnnotationMeta<?> requestMapping = methodMeta.findMergedAnnotation(Annotations.RequestMapping);
        AnnotationMeta<?> httpExchange = methodMeta.findMergedAnnotation(Annotations.HttpExchange);
        if (requestMapping == null && httpExchange == null) {
            // 提取 @ExceptionHandler 标注的方法
            // service 类中也可以通过 @ExceptionHandler 来标注错误处理方法
            AnnotationMeta<?> exceptionHandler = methodMeta.getAnnotation(Annotations.ExceptionHandler);
            if (exceptionHandler != null) {
                // 封装方法参数相关的元信息 MethodParameterMeta，包括继承体系中所有的 Parameter ， 参数名称, 参数index
                methodMeta.initParameters();
                methodMeta.getServiceMeta().addExceptionHandler(methodMeta);
            }
            return null;
        }

        ServiceMeta serviceMeta = methodMeta.getServiceMeta();
        String name = methodMeta.getMethod().getName();
        String[] methods = requestMapping == null
                ? httpExchange.getStringArray("method")
                : requestMapping.getStringArray("method");
        String[] paths = requestMapping == null ? httpExchange.getValueArray() : requestMapping.getValueArray();
        if (paths.length == 0) {
            paths = new String[] {'/' + name};
        }
        return builder(requestMapping, httpExchange, methodMeta.findMergedAnnotation(Annotations.ResponseStatus))
                .method(methods)
                .name(name)
                .path(paths)
                .contextPath(serviceMeta.getContextPath())
                .service(serviceMeta.getServiceGroup(), serviceMeta.getServiceVersion())
                .cors(buildCorsMeta(methodMeta.findMergedAnnotation(Annotations.CrossOrigin), methods))
                .build(); // 创建初始化各种 Condition （方法级）
    }

    private Builder builder(
            AnnotationMeta<?> requestMapping, AnnotationMeta<?> httpExchange, AnnotationMeta<?> responseStatus) {
        Builder builder = RequestMapping.builder();
        if (responseStatus != null) {
            HttpStatus value = responseStatus.getEnum("value");
            builder.responseStatus(value.value());
            String reason = responseStatus.getString("reason");
            if (StringUtils.isNotEmpty(reason)) {
                builder.responseReason(reason);
            }
        }
        if (requestMapping == null) {
            return builder.consume(httpExchange.getStringArray("contentType"))
                    .produce(httpExchange.getStringArray("accept"));
        }
        return builder.param(requestMapping.getStringArray("params"))
                .header(requestMapping.getStringArray("headers"))
                .consume(requestMapping.getStringArray("consumes"))
                .produce(requestMapping.getStringArray("produces"));
    }

    private CorsMeta buildCorsMeta(AnnotationMeta<?> crossOrigin, String[] methods) {
        if (globalCorsMeta == null) {
            globalCorsMeta = CorsUtils.getGlobalCorsMeta(restConfig);
        }
        if (crossOrigin == null) {
            return globalCorsMeta;
        }
        String[] allowedMethods = crossOrigin.getStringArray("methods");
        if (allowedMethods.length == 0) {
            allowedMethods = methods;
            if (allowedMethods.length == 0) {
                allowedMethods = new String[] {CommonConstants.ANY_VALUE};
            }
        }
        CorsMeta corsMeta = CorsMeta.builder()
                .allowedOrigins(crossOrigin.getStringArray("origins"))
                .allowedMethods(allowedMethods)
                .allowedHeaders(crossOrigin.getStringArray("allowedHeaders"))
                .exposedHeaders(crossOrigin.getStringArray("exposedHeaders"))
                .allowCredentials(crossOrigin.getString("allowCredentials"))
                .maxAge(crossOrigin.getNumber("maxAge"))
                .build();
        return globalCorsMeta.combine(corsMeta);
    }
}
