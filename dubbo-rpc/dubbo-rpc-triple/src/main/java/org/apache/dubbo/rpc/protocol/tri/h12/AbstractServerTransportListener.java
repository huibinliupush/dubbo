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
package org.apache.dubbo.rpc.protocol.tri.h12;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.constants.LoggerCodeConstants;
import org.apache.dubbo.common.logger.FluentLogger;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.threadpool.serial.SerializingExecutor;
import org.apache.dubbo.common.utils.MethodUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.remoting.http12.HttpChannel;
import org.apache.dubbo.remoting.http12.HttpInputMessage;
import org.apache.dubbo.remoting.http12.HttpStatus;
import org.apache.dubbo.remoting.http12.HttpTransportListener;
import org.apache.dubbo.remoting.http12.RequestMetadata;
import org.apache.dubbo.remoting.http12.exception.HttpStatusException;
import org.apache.dubbo.remoting.http12.message.MethodMetadata;
import org.apache.dubbo.rpc.HeaderFilter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.protocol.tri.DescriptorUtils;
import org.apache.dubbo.rpc.protocol.tri.ExceptionUtils;
import org.apache.dubbo.rpc.protocol.tri.RpcInvocationBuildContext;
import org.apache.dubbo.rpc.protocol.tri.TripleConstants;
import org.apache.dubbo.rpc.protocol.tri.TripleHeaderEnum;
import org.apache.dubbo.rpc.protocol.tri.TripleProtocol;
import org.apache.dubbo.rpc.protocol.tri.route.DefaultRequestRouter;
import org.apache.dubbo.rpc.protocol.tri.route.RequestRouter;
import org.apache.dubbo.rpc.protocol.tri.stream.StreamUtils;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
// HEADER :  RequestMetadata
// MESSAGE : HttpInputMessage
public abstract class AbstractServerTransportListener<HEADER extends RequestMetadata, MESSAGE extends HttpInputMessage>
        implements HttpTransportListener<HEADER, MESSAGE> {

    private static final FluentLogger LOGGER = FluentLogger.of(AbstractServerTransportListener.class);
    private static final String HEADER_FILTERS_CACHE = "HEADER_FILTERS_CACHE";

    private final FrameworkModel frameworkModel;
    private final URL url;
    private final HttpChannel httpChannel;
    private final RequestRouter requestRouter;
    // 封装 exceptionHandlers
    private final ExceptionCustomizerWrapper exceptionCustomizerWrapper;
    // 用 SerializingExecutor 封装 executor, SerializingExecutor 的目的就是按照 task 的提交顺序严格串行执行（同时只能执行一个任务）
    private Executor executor;
    // DefaultHttp1Request 封装 rest 请求的元数据（headers , method , uri , body）
    private HEADER httpMetadata;
    // 后续会在 org.apache.dubbo.rpc.protocol.tri.h12.AbstractServerTransportListener.doRoute 中进行设置
    private RpcInvocationBuildContext context;
    // DefaultHttpMessageListener 封装了 HttpMessageDecoder , 方法的请求参数类型。以及 serverCallListener::onMessage 回调函数
    // 由 org.apache.dubbo.rpc.protocol.tri.h12.http1.DefaultHttp11ServerTransportListener.buildHttpMessageListener 创建
    private HttpMessageListener httpMessageListener;

    protected AbstractServerTransportListener(FrameworkModel frameworkModel, URL url, HttpChannel httpChannel) {
        this.frameworkModel = frameworkModel;
        // server 端 url
        this.url = url;
        // NettyHttp1Channel
        this.httpChannel = httpChannel;
        // 通过 rest 与 dubbo 处理 handler 的映射关系查找处理 rest 请求的 handler
        requestRouter = frameworkModel.getOrRegisterBean(DefaultRequestRouter.class);
        // 封装 exceptionHandlers
        exceptionCustomizerWrapper = new ExceptionCustomizerWrapper(frameworkModel);
    }
    // DefaultHttp1Request -> metadata
    @Override
    public final void onMetadata(HEADER metadata) {
        httpMetadata = metadata;
        exceptionCustomizerWrapper.setMetadata(metadata);

        try {
            // radixTree 中查找 rest request 映射 handler(处理元信息)
            // 根据 mediaType 获取 rest 请求体，响应体的 codec
            // 初始化 RpcInvocationBuildContext （封装处理 rest 请求的所有元数据）
            onBeforeMetadata(metadata);
        } catch (Throwable t) {
            logError(t);
            onMetadataError(metadata, t);
            return;
        }

        try {
            /**
             * 根据 providerurl 获取服务暴露时创建的线程池
             * org.apache.dubbo.remoting.transport.AbstractServer#AbstractServer(org.apache.dubbo.common.URL, org.apache.dubbo.remoting.ChannelHandler)
             *
             * 用 SerializingExecutor 封装 executor, SerializingExecutor 的目的就是按照 task 的提交顺序严格串行执行（同时只能执行一个任务）
             * 保证 task 的顺序执行
             * */
            executor = initializeExecutor(url, metadata);
        } catch (Throwable t) {
            LOGGER.error(LoggerCodeConstants.COMMON_ERROR_USE_THREAD_POOL, "Initialize executor failed.", t);
            onError(t);
            return;
        }
        if (executor == null) {
            LOGGER.internalError("Executor must not be null.");
            onError(new NullPointerException("Initialize executor return null"));
            return;
        }
        // SerializingExecutor 保证先提交的任务一定会被先执行，后提交的任务后执行，保证任务的执行顺序，不会并发执行
        executor.execute(() -> {
            try {
                onPrepareMetadata(metadata);
                // DefaultHttpMessageListener 封装了 HttpMessageDecoder , 方法的请求参数类型。以及 serverCallListener::onMessage 回调函数
                setHttpMessageListener(buildHttpMessageListener());
                // responseObserver 中设置 HttpMessageEncoder
                onMetadataCompletion(metadata);
            } catch (Throwable t) {
                logError(t);
                onMetadataError(metadata, t);
            }
        });
    }

    protected void onBeforeMetadata(HEADER metadata) {
        doRoute(metadata);
    }

    protected final void doRoute(HEADER metadata) {
        // RpcInvocationBuildContext 封装处理 rest 请求的所有元信息，后续用于初始化 RpcInvocation
        context = requestRouter.route(url, metadata, httpChannel);
        if (context == null) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND.getCode(), "Invoker not found");
        }
        exceptionCustomizerWrapper.setMethodDescriptor(context.getMethodDescriptor());
    }

    protected Executor initializeExecutor(URL url, HEADER metadata) {
        // providerURl
        url = context.getInvoker().getUrl();
        return getExecutor(url, url);
    }

    protected final Executor getExecutor(URL url, Object data) { // providerURl
        // 1. 首先通过 ApplicationConfig::getExecutorManagementMode 获取 ExecutorRepository 类型
        //    默认为 IsolationExecutorRepository
        // 2. 取具体协议对应的 ExecutorSupport，triple 协议对应 TripleIsolationExecutorSupport，dubbo  协议对应 DubboIsolationExecutorSupport
        // 3. ExecutorSupport 根据 providerURl 获取对应的线程池 executor
        return new SerializingExecutor(ExecutorRepository.getInstance(url.getOrDefaultApplicationModel())
                .getExecutorSupport(url)
                .getExecutor(data));
    }

    protected void onPrepareMetadata(HEADER metadata) {
        // default no op
    }

    protected abstract HttpMessageListener buildHttpMessageListener();

    protected void onMetadataCompletion(HEADER metadata) {
        // default no op
    }

    protected void onMetadataError(HEADER metadata, Throwable throwable) {
        initializeAltSvc(url);
        onError(throwable);
    }

    /**
     * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Alt-Svc">Alt-Svc</a>
     */
    protected void initializeAltSvc(URL url) {}
    // DefaultHttp1Request -> message
    @Override
    public final void onData(MESSAGE message) {
        if (executor == null) {
            // message close
            onDataFinally(message);
            return;
        }
        executor.execute(() -> {
            try {
                doOnData(message);
            } catch (Throwable t) {
                logError(t);
                onError(message, t);
            } finally {
                onDataFinally(message);
            }
        });
    }
    // DefaultHttp1Request -> message
    protected void doOnData(MESSAGE message) {
        if (httpMessageListener == null) {
            return;
        }
        onPrepareData(message);
        // DefaultHttpMessageListener, 对 body 进行 decode
        // org.apache.dubbo.rpc.protocol.tri.h12.http1.DefaultHttp11ServerTransportListener.buildHttpMessageListener
        httpMessageListener.onMessage(message.getBody());
        onDataCompletion(message);
    }

    protected void onPrepareData(MESSAGE message) {
        // default no op
    }

    protected void onDataCompletion(MESSAGE message) {
        // default no op
    }

    protected void onDataFinally(MESSAGE message) {
        try {
            message.close();
        } catch (Exception e) {
            onError(e);
        }
    }

    protected void onError(MESSAGE message, Throwable throwable) {
        onError(throwable);
    }

    protected void onError(Throwable throwable) {
        throw ExceptionUtils.wrap(throwable);
    }

    private void logError(Throwable t) {
        Supplier<String> msg = () -> {
            StringBuilder sb = new StringBuilder(128);
            sb.append("An error occurred while processing the http request with ")
                    .append(getClass().getSimpleName())
                    .append(", ")
                    .append(httpMetadata);
            if (TripleProtocol.VERBOSE_ENABLED) {
                sb.append(", headers=").append(httpMetadata.headers());
            }
            if (context != null) {
                MethodDescriptor md = context.getMethodDescriptor();
                if (md != null) {
                    sb.append(", method=").append(MethodUtils.toShortString(md));
                }
                if (TripleProtocol.VERBOSE_ENABLED) {
                    Invoker<?> invoker = context.getInvoker();
                    if (invoker != null) {
                        URL url = invoker.getUrl();
                        Object service = url.getServiceModel().getProxyObject();
                        sb.append(", service=")
                                .append(service.getClass().getSimpleName())
                                .append('@')
                                .append(Integer.toHexString(System.identityHashCode(service)))
                                .append(", url='")
                                .append(url)
                                .append('\'');
                    }
                }
            }
            return sb.toString();
        };
        Throwable th = ExceptionUtils.unwrap(t);
        LOGGER.msg(msg).log(exceptionCustomizerWrapper.resolveLogLevel(th), th);
    }

    protected final RpcInvocation buildRpcInvocation(RpcInvocationBuildContext context) {
        MethodDescriptor methodDescriptor = context.getMethodDescriptor();
        if (methodDescriptor == null) {
            methodDescriptor = DescriptorUtils.findMethodDescriptor(
                    context.getServiceDescriptor(), context.getMethodName(), context.isHasStub());
            setMethodDescriptor(methodDescriptor);
        }
        // 主要封装 server 方法的请求类型和响应类型
        MethodMetadata methodMetadata = context.getMethodMetadata();
        if (methodMetadata == null) {
            methodMetadata = MethodMetadata.fromMethodDescriptor(methodDescriptor);
            context.setMethodMetadata(methodMetadata);
        }
        // 后端的 DubboInvoker(带有 Filter 链)
        Invoker<?> invoker = context.getInvoker();
        // providerUrl
        URL url = invoker.getUrl();
        RpcInvocation inv = new RpcInvocation(
                url.getServiceModel(),
                methodDescriptor.getMethodName(),
                context.getServiceDescriptor().getInterfaceName(),
                url.getProtocolServiceKey(),
                methodDescriptor.getParameterClasses(),
                new Object[0]);
        inv.setTargetServiceUniqueName(url.getServiceKey());
        inv.setReturnTypes(methodDescriptor.getReturnTypes());
        // http 请求中的 headers 会转换为 RPC 中的 attachments
        inv.setObjectAttachments(StreamUtils.toAttachments(httpMetadata.headers()));
        // 设置 attributes
        inv.put(TripleConstants.REMOTE_ADDRESS_KEY, httpChannel.remoteAddress());
        // context 的相关 attributes 设置，see：org.apache.dubbo.rpc.protocol.tri.route.DefaultRequestRouter.route
        inv.getAttributes().putAll(context.getAttributes());
        // 获取 http 请求中的 header —— tri-consumer-appname
        String consumerAppName = httpMetadata.header(TripleHeaderEnum.CONSUMER_APP_NAME_KEY.getKey());
        if (consumerAppName != null) {
            inv.put(TripleHeaderEnum.CONSUMER_APP_NAME_KEY, consumerAppName);
        }

        // customizer RpcInvocation
        HeaderFilter[] headerFilters =
                UrlUtils.computeServiceAttribute(invoker.getUrl(), HEADER_FILTERS_CACHE, this::loadHeaderFilters);
        if (headerFilters == null) {
            headerFilters = this.loadHeaderFilters(invoker.getUrl());
        }
        for (HeaderFilter headerFilter : headerFilters) {
            // headerFilter 用于跨域，认证（Authenticator），token 等过滤请求
            // 不符合直接抛出异常
            headerFilter.invoke(invoker, inv);
        }
        // responseObserver.addHeadersCustomizer
        initializeAltSvc(url);
        // 解析 header 中配置的 timeout， 设置到 RpcInvocation 中的 attributes
        return onBuildRpcInvocationCompletion(inv);
    }

    private HeaderFilter[] loadHeaderFilters(URL url) {
        List<HeaderFilter> headerFilters = frameworkModel
                .getExtensionLoader(HeaderFilter.class)
                .getActivateExtension(url, CommonConstants.HEADER_FILTER_KEY);
        LOGGER.info("Header filters for [{}] loaded: {}", url, headerFilters);
        return headerFilters.toArray(new HeaderFilter[0]);
    }

    protected RpcInvocation onBuildRpcInvocationCompletion(RpcInvocation invocation) {
        String timeoutString = httpMetadata.header(TripleHeaderEnum.SERVICE_TIMEOUT.getKey());
        try {
            if (timeoutString != null) {
                Long timeout = Long.parseLong(timeoutString);
                invocation.put(CommonConstants.TIMEOUT_KEY, timeout);
            }
        } catch (Throwable t) {
            LOGGER.warn(
                    LoggerCodeConstants.PROTOCOL_FAILED_PARSE,
                    "Failed to parse request timeout set from: {}, service={}, method={}",
                    timeoutString,
                    context.getServiceDescriptor().getInterfaceName(),
                    context.getMethodName());
        }
        return invocation;
    }

    protected final FrameworkModel getFrameworkModel() {
        return frameworkModel;
    }

    protected final ExceptionCustomizerWrapper getExceptionCustomizerWrapper() {
        return exceptionCustomizerWrapper;
    }

    protected final HEADER getHttpMetadata() {
        return httpMetadata;
    }

    public final RpcInvocationBuildContext getContext() {
        return context;
    }

    protected final void setHttpMessageListener(HttpMessageListener httpMessageListener) {
        this.httpMessageListener = httpMessageListener;
    }

    protected Function<Throwable, Object> getExceptionCustomizer() {
        return exceptionCustomizerWrapper::customize;
    }

    protected void setMethodDescriptor(MethodDescriptor methodDescriptor) {
        context.setMethodDescriptor(methodDescriptor);
        exceptionCustomizerWrapper.setMethodDescriptor(methodDescriptor);
    }
}
