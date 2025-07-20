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
package org.apache.dubbo.rpc.protocol.tri.rest.mapping;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.FluentLogger;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.nested.RestConfig;
import org.apache.dubbo.remoting.http12.HttpRequest;
import org.apache.dubbo.remoting.http12.exception.HttpStatusException;
import org.apache.dubbo.remoting.http12.message.MethodMetadata;
import org.apache.dubbo.remoting.http12.rest.OpenAPIService;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.model.ReflectionMethodDescriptor;
import org.apache.dubbo.rpc.model.ReflectionServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.protocol.tri.DescriptorUtils;
import org.apache.dubbo.rpc.protocol.tri.TripleProtocol;
import org.apache.dubbo.rpc.protocol.tri.rest.Messages;
import org.apache.dubbo.rpc.protocol.tri.rest.RestConstants;
import org.apache.dubbo.rpc.protocol.tri.rest.RestMappingException;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.RadixTree.Match;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition.PathExpression;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition.ProducesCondition;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.HandlerMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.MethodMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ServiceMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.util.KeyString;
import org.apache.dubbo.rpc.protocol.tri.rest.util.MethodWalker;
import org.apache.dubbo.rpc.protocol.tri.rest.util.PathUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class DefaultRequestMappingRegistry implements RequestMappingRegistry {

    private static final FluentLogger LOGGER = FluentLogger.of(DefaultRequestMappingRegistry.class);

    private final FrameworkModel frameworkModel;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicBoolean initialized = new AtomicBoolean();

    private ContentNegotiator contentNegotiator;
    private OpenAPIService openAPIService;
    private List<RequestMappingResolver> resolvers;
    private RestConfig restConfig;
    private RadixTree<Registration> tree;

    public DefaultRequestMappingRegistry(FrameworkModel frameworkModel) {
        this.frameworkModel = frameworkModel;
    }

    private void init(Invoker<?> invoker) {
        contentNegotiator = frameworkModel.getOrRegisterBean(ContentNegotiator.class);
        if (TripleProtocol.OPENAPI_ENABLED) {
            openAPIService = frameworkModel.getBean(OpenAPIService.class);
        }
        resolvers = frameworkModel.getActivateExtensions(RequestMappingResolver.class);
        restConfig = ConfigManager.getProtocolOrDefault(invoker.getUrl())
                .getTripleOrDefault()
                .getRestOrDefault();
        for (RequestMappingResolver resolver : resolvers) {
            resolver.setRestConfig(restConfig);
        }
        tree = new RadixTree<>(restConfig.getCaseSensitiveMatchOrDefault());
    }

    @Override
    public void register(Invoker<?> invoker) {
        if (tree == null) {
            lock.writeLock().lock();
            try {
                if (initialized.compareAndSet(false, true)) {
                    init(invoker);
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        // tri://192.168.2.101:50052/org.example.RestTestService?anyhost=true&application=dubbo-springboot-triple-rest-springmvc&background=false&bind.ip=192.168.2.101&bind.port=50052&deprecated=false&dubbo=2.0.2&dynamic=true&executor-management-mode=isolation&file-cache=true&generic=false&interface=org.example.RestTestService&methods=getHead,getMuchParam,getMuchVariable,getReg,patchById,postList,postUseConsumesUser,postUseParams&pid=5532&prefer.serialization=hessian2,fastjson2&qos.enable=false&register=false&release=3.3.4&side=provider&timestamp=1752216249480&triple.rest.enable.default.mapping=false&triple.verbose=true
        // 获取服务暴露的 URL
        URL url = invoker.getUrl();
        // 获取服务 impl 实例 RestTestServiceImpl
        Object service = url.getServiceModel().getProxyObject();
        // 从 ModuleServiceRepository 中获取注册的 ServiceDescriptor
        // see : org.apache.dubbo.config.ServiceConfig.doExportUrls
        ServiceDescriptor sd = DescriptorUtils.getReflectionServiceDescriptor(url);
        if (sd == null) {
            return;
        }
        AtomicInteger counter = new AtomicInteger();
        long start = System.currentTimeMillis();
        // 开始注册 service 的 rest request mapping
        // service.getClass() 是实现类 RestTestServiceImpl
        new MethodWalker().walk(service.getClass(), (classes, consumer) -> {
            for (int i = 0, size = resolvers.size(); i < size; i++) {
                // BasicRequestMappingResolver 负责解析，注册 rest request path : /{interfaceName}/{methodName}
                // 可以通过 org.apache.dubbo.config.nested.RestConfig.enableDefaultMapping 关闭

                // SpringMvcRequestMappingResolver 负责解析，注册 spring mvc 注解标注的  rest request path
                RequestMappingResolver resolver = resolvers.get(i);
                ServiceMeta serviceMeta = new ServiceMeta(classes, sd, service, url, resolver.getRestToolKit());
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "{} resolving rest mappings for {} at url [{}]",
                            resolver.getClass().getSimpleName(),
                            serviceMeta,
                            url.toString(""));
                }
                if (!resolver.accept(serviceMeta)) {
                    continue;
                }
                // 从 serviceMeta 中提取 rest mapping 的源数据 —— RequestMapping
                // class 级别的 rest mapping 元数据
                RequestMapping classMapping = resolver.resolve(serviceMeta);
                // 开始单个 method 级别的 rest mapping
                consumer.accept((methods) -> {
                    // methods 为 service 继承关系中所有类中对应的同一 method
                    // 比如：接口定义了一个 method , 实现类实现了这个 method
                    // 那么这里就是两个 method, 因为 mvc 注解可以标注在继承关系中的任意一个地方
                    // 所以需要全量查找
                    Method method = methods.get(0);
                    MethodDescriptor md = sd.getMethod(method.getName(), method.getParameterTypes());
                    MethodMeta methodMeta = new MethodMeta(methods, md, serviceMeta);
                    if (!resolver.accept(methodMeta)) {
                        return;
                    }
                    // 从 MethodMeta 中提取 rest mapping 的源数据 —— RequestMapping(方法级)
                    // RequestMapping 中包含了各种 conditions
                    RequestMapping methodMapping = resolver.resolve(methodMeta);
                    if (methodMapping == null || methodMapping.getPathCondition() == null) {
                        return;
                    }
                    if (md == null) {
                        if (!(sd instanceof ReflectionServiceDescriptor)) {
                            return;
                        }
                        md = new ReflectionMethodDescriptor(method);
                        ((ReflectionServiceDescriptor) sd).addMethod(md);
                        methodMeta.setMethodDescriptor(md);
                    }
                    if (classMapping != null) {
                        // 将 class 上标注的 @RequestMapping 注解相关的属性合并到 method 上
                        // 其中主要是 pathCondition 的合并，合并出一个完整的 rest path
                        methodMapping = classMapping.combine(methodMapping);
                    }
                    // 封装方法参数相关的元信息 MethodParameterMeta，包括继承体系中所有的 Parameter ， 参数名称, 参数index
                    methodMeta.initParameters();
                    // 封装 actualRequestTypes ， actualResponseType 到 MethodMetadata 中
                    MethodMetadata methodMetadata = MethodMetadata.fromMethodDescriptor(md);
                    // 将 rest 方法映射的所有元信息注册到 RadixTree 中
                    register0(methodMapping, new HandlerMeta(invoker, methodMeta, methodMetadata, md, sd), counter);
                });
            }
        });
        onMappingChanged();
        LOGGER.info(
                "Registered {} rest mappings for service [{}] at url [{}] in {}ms",
                counter,
                ClassUtils.toShortString(service),
                url.toString(""),
                System.currentTimeMillis() - start);
    }

    private void register0(RequestMapping mapping, HandlerMeta handler, AtomicInteger counter) {
        lock.writeLock().lock();
        try {
            // 封装 rest 映射元信息 RequestMapping ， rest 请求的处理 handler 元信息 HandlerMeta
            Registration registration = new Registration(mapping, handler);
            // 构建 PathExpression ， 解析相关的 PathSegment
            for (PathExpression path : mapping.getPathCondition().getExpressions()) {
                // 按照 path 的元信息 PathExpression 将 registration 注册到 RadixTree 中
                Registration exists = tree.addPath(path, registration);
                if (exists == null) {
                    counter.incrementAndGet();
                    if (LOGGER.isDebugEnabled()) {
                        String msg = "Register rest mapping: '{}' -> mapping={}, method={}";
                        LOGGER.debug(msg, path, mapping, handler.getMethod());
                    }
                } else if (LOGGER.isWarnEnabled()) {
                    LOGGER.internalWarn(Messages.DUPLICATE_MAPPING.format(path, mapping, handler.getMethod(), exists));
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void unregister(Invoker<?> invoker) {
        if (tree == null) {
            return;
        }

        lock.writeLock().lock();
        try {
            tree.remove(r -> r.getMeta().getInvoker() == invoker);
            onMappingChanged();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void destroy() {
        if (tree == null) {
            return;
        }

        lock.writeLock().lock();
        try {
            tree.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public HandlerMeta lookup(HttpRequest request) {
        if (tree == null) {
            return null;
        }
        // /demo/post/list
        // /demo/get/muchVariable/345/muchvalue
        String stringPath = PathUtils.normalize(request.uri());
        request.setAttribute(RestConstants.PATH_ATTRIBUTE, stringPath);
        // 将 rest path 封装成 KeyString, 因为在 radixTree 中映射路径的时候，是通过 KeyString 来映射的
        // see : org.apache.dubbo.rpc.protocol.tri.rest.mapping.RadixTree.addPath(org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition.PathExpression, T)
        KeyString path = new KeyString(stringPath, restConfig.getCaseSensitiveMatchOrDefault());
        // 存放匹配成功的映射信息
        List<Candidate> candidates = new ArrayList<>();
        // candidates 为空，表示相关的映射 condition 不匹配，比如，用 POST 方法请求映射的 GET 方法
        // 将匹配失败的 RequestMapping 加入到 partialMatches 集合中
        List<RequestMapping> partialMatches = new LinkedList<>();
        // 通过 path 到 radixTree 中查找映射关系
        tryMatch(request, path, candidates, partialMatches);

        if (candidates.isEmpty()) {
            int end = path.length();
            // --- /users also matches to /users/
            if (end > 1 && restConfig.getTrailingSlashMatchOrDefault()) {
                if (path.charAt(end - 1) == '/') {
                    tryMatch(request, path.subSequence(0, --end), candidates, partialMatches);
                }
            }

            if (candidates.isEmpty()) {
                for (int i = end - 1; i >= 0; i--) {
                    char ch = path.charAt(i);
                    if (ch == '/') {
                        break;
                    }
                    // /users also matches to /users.*
                    if (ch == '.' && restConfig.getSuffixPatternMatchOrDefault()) {
                        if (contentNegotiator.supportExtension(path.toString(i + 1, end))) {
                            tryMatch(request, path.subSequence(0, i), candidates, partialMatches);
                            if (!candidates.isEmpty()) {
                                break;
                            }
                            end = i;
                        }
                    }
                    if (ch == '~') {
                        request.setAttribute(RestConstants.SIG_ATTRIBUTE, path.toString(i + 1, end));
                        tryMatch(request, path.subSequence(0, i), candidates, partialMatches);
                        if (!candidates.isEmpty()) {
                            break;
                        }
                    }
                }
            }
        }

        int size = candidates.size();
        if (size == 0) {
            // 根据 partialMatches 处理没有匹配成功的情况
            // throw 4xx 的 HttpStatusException
            handleNoMatch(request, partialMatches);
            return null;
        }
        if (size > 1) {
            candidates.sort((c1, c2) -> {
                int comparison = c1.expression.compareTo(c2.expression, stringPath);
                if (comparison != 0) {
                    return comparison;
                }
                comparison = c1.mapping.compareTo(c2.mapping, request);
                if (comparison != 0) {
                    return comparison;
                }
                return c1.variableMap.size() - c2.variableMap.size();
            });

            LOGGER.debug("Candidate rest mappings: {}", candidates);

            Candidate first = candidates.get(0);
            Candidate second = candidates.get(1);
            if (first.mapping.compareTo(second.mapping, request) == 0) {
                throw new RestMappingException(Messages.AMBIGUOUS_MAPPING, path, first, second);
            }
        }

        Candidate winner = candidates.get(0);
        RequestMapping mapping = winner.mapping;
        HandlerMeta handler = winner.meta;
        request.setAttribute(RestConstants.MAPPING_ATTRIBUTE, mapping);
        request.setAttribute(RestConstants.HANDLER_ATTRIBUTE, handler);

        LOGGER.debug("Matched rest mapping={}, method={}", mapping, handler.getMethod());

        if (!winner.variableMap.isEmpty()) {
            // 对于 /demo/get/muchVariable/{id}/{name} -- /demo/get/muchVariable/345/muchvalue 来说
            // 这里会存放提取到的路径变量
            // see : org.apache.dubbo.rpc.protocol.tri.rest.mapping.RadixTree.matchRecursive
            request.setAttribute(RestConstants.URI_TEMPLATE_VARIABLES_ATTRIBUTE, winner.variableMap);
        }

        ProducesCondition producesCondition = mapping.getProducesCondition();
        if (producesCondition != null) {
            request.setAttribute(RestConstants.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE, producesCondition.getMediaTypes());
        }

        return handler;
    }

    private void tryMatch(
            HttpRequest request, KeyString path, List<Candidate> candidates, List<RequestMapping> partialMatches) {
        List<Match<Registration>> matches = new ArrayList<>();

        lock.readLock().lock();
        try {
            // 通过 path 到 radixTree 中查找匹配到的 dubbo 处理 handler 元数据（Registration）
            // 在注册 Registration 的时候（RadixTree.addPath），对于 directPath 来说，会将 PathExpression 和 Registration 封装成 Match
            // Match 作为映射 value , 所以这里我们查找的就是这个 match ,所有匹配到的都会加入到这里的 matches 集合中
            // see : org.apache.dubbo.rpc.protocol.tri.rest.mapping.RadixTree.addPath(org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition.PathExpression, T)
            tree.match(path, matches);
        } finally {
            lock.readLock().unlock();
        }

        int size = matches.size();
        // 没有匹配到 rest path 的映射信息直接返回
        if (size == 0) {
            return;
        }
        /**
         * 到这里我们已经匹配到了 rest path 的映射信息，但为什么会匹配到多个呢 ？
         * 比如我们工程中存在两个映射：1. /demo/post/list（direct path） 2. /demo/post/{id}
         * 这样就会匹配到两个 Match
         * */
        for (int i = 0; i < size; i++) {
            Match<Registration> match = matches.get(i);

            /**
             * Match 的 value 中存放的是 Registration，而 Registration 中封装的是映射的元信息 RequestMapping，以及负责处理请求的元信息 HandlerMeta
             * RequestMapping 中封装了一系列的映射条件，比如:
             *      MethodsCondition，我是 get 方法，你就不能通过 post 来请求
             *      ParamsCondition 要求请求参数中必须带有特定的参数名以及参数值
             *      HeadersCondition 要求请求 headers 中必须带有特定的 header 和 header 值
             *      ConsumesCondition 要求请求的 content-type 必须匹配
             *      CorsMeta 跨域的一些要求
             *
             * 所以找到了 Match 之后，我们还需要通过 RequestMapping 中的 Condition 进行匹配，完全匹配之后，才算真正的匹配成功
             * 因为有时候虽然 rest path 匹配了，但是相关的 Condition 不匹配
             *
             * 匹配失败返回 null, 匹配成功返回 RequestMapping ，里面封装了匹配时用到的 conditions
             * */
            RequestMapping mapping = match.getValue().getMapping().match(request, match.getExpression());
            if (mapping != null) {
                Candidate candidate = new Candidate();
                // 映射的元信息 RequestMapping 封装了一系列的映射条件 conditions
                candidate.mapping = mapping;
                // 负责处理请求的元信息 HandlerMeta
                candidate.meta = match.getValue().getMeta();
                // PathExpression
                candidate.expression = match.getExpression();
                // 对于 /demo/get/muchVariable/{id}/{name} -- /demo/get/muchVariable/345/muchvalue 来说
                // 这里会存放提取到的路径变量
                // see : org.apache.dubbo.rpc.protocol.tri.rest.mapping.RadixTree.matchRecursive
                candidate.variableMap = match.getVariableMap();
                candidates.add(candidate);
            }
        }
        // candidates 为空，表示相关的映射 condition 不匹配，比如，用 POST 方法请求映射的 GET 方法
        if (candidates.isEmpty()) {
            for (int i = 0; i < size; i++) {
                // 将匹配失败的 RequestMapping 加入到 partialMatches 集合中
                partialMatches.add(matches.get(i).getValue().getMapping());
            }
        }
    }

    private void handleNoMatch(HttpRequest request, List<RequestMapping> partialMatches) {
        if (partialMatches.isEmpty()) {
            return;
        }
        boolean methodsMismatch = true;
        boolean consumesMismatch = true;
        boolean producesMismatch = true;
        boolean paramsMismatch = true;
        for (RequestMapping mapping : partialMatches) {
            if (methodsMismatch) {
                methodsMismatch = !mapping.matchMethod(request.method());
            }
            if (consumesMismatch) {
                consumesMismatch = !mapping.matchConsumes(request);
            }
            if (producesMismatch) {
                producesMismatch = !mapping.matchProduces(request);
            }
            if (paramsMismatch) {
                paramsMismatch = !mapping.matchParams(request);
            }
        }
        if (methodsMismatch) {
            throw new HttpStatusException(405, "Request method '" + request.method() + "' not supported");
        }
        if (consumesMismatch) {
            throw new HttpStatusException(415, "Content type '" + request.contentType() + "' not supported");
        }
        if (producesMismatch) {
            throw new HttpStatusException(406, "Could not find acceptable representation");
        }
        if (paramsMismatch) {
            throw new HttpStatusException(400, "Unsatisfied query parameter conditions");
        }
    }

    @Override
    public boolean exists(String stringPath, String method) {
        if (tree == null) {
            return false;
        }

        KeyString path = new KeyString(stringPath, restConfig.getCaseSensitiveMatchOrDefault());
        if (tryExists(path, method)) {
            return true;
        }

        int end = path.length();
        if (restConfig.getTrailingSlashMatchOrDefault()) {
            if (path.charAt(end - 1) == '/') {
                end--;
                if (tryExists(path.subSequence(0, end - 1), method)) {
                    return true;
                }
            }
        }

        for (int i = end - 1; i >= 0; i--) {
            char ch = path.charAt(i);
            if (ch == '/') {
                break;
            }
            if (ch == '.' && restConfig.getSuffixPatternMatchOrDefault()) {
                if (contentNegotiator.supportExtension(path.toString(i + 1, end))) {
                    if (tryExists(path.subSequence(0, i), method)) {
                        return true;
                    }
                    end = i;
                }
            }
            if (ch == '~') {
                return tryExists(path.subSequence(0, i), method);
            }
        }

        return false;
    }

    @Override
    public Collection<Registration> getRegistrations() {
        lock.readLock().lock();
        try {
            Map<Registration, Boolean> registrations = new IdentityHashMap<>();
            tree.walk((expr, registration) -> registrations.put(registration, Boolean.TRUE));
            return registrations.keySet();
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean tryExists(KeyString path, String method) {
        List<Match<Registration>> matches = new ArrayList<>();
        lock.readLock().lock();
        try {
            tree.match(path, matches);
        } finally {
            lock.readLock().unlock();
        }
        for (int i = 0, size = matches.size(); i < size; i++) {
            if (matches.get(i).getValue().getMapping().matchMethod(method)) {
                return true;
            }
        }
        return false;
    }

    private void onMappingChanged() {
        if (openAPIService != null) {
            openAPIService.refresh();
        }
    }

    private static final class Candidate {
        // 映射的元信息 RequestMapping 封装了一系列的映射条件 conditions
        RequestMapping mapping;
        // 负责处理请求的元信息 HandlerMeta
        HandlerMeta meta;
        PathExpression expression;
        // 对于 /demo/get/muchVariable/{id}/{name} -- /demo/get/muchVariable/345/muchvalue 来说
        // 这里会存放提取到的路径变量
        // see : org.apache.dubbo.rpc.protocol.tri.rest.mapping.RadixTree.matchRecursive
        Map<String, String> variableMap;

        @Override
        public String toString() {
            return "Candidate{mapping=" + mapping + ", method=" + meta.getMethod() + '}';
        }
    }
}
