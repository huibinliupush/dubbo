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
package org.apache.dubbo.rpc.protocol.tri.h12.http1;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.http12.HttpChannel;
import org.apache.dubbo.remoting.http12.HttpHeaderNames;
import org.apache.dubbo.remoting.http12.HttpInputMessage;
import org.apache.dubbo.remoting.http12.RequestMetadata;
import org.apache.dubbo.remoting.http12.h1.Http1ServerChannelObserver;
import org.apache.dubbo.remoting.http12.h1.Http1ServerTransportListener;
import org.apache.dubbo.remoting.http12.h1.Http1SseServerChannelObserver;
import org.apache.dubbo.remoting.http12.message.DefaultListeningDecoder;
import org.apache.dubbo.remoting.http12.message.MediaType;
import org.apache.dubbo.remoting.http12.message.codec.JsonCodec;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.protocol.tri.Http3Exchanger;
import org.apache.dubbo.rpc.protocol.tri.RpcInvocationBuildContext;
import org.apache.dubbo.rpc.protocol.tri.h12.AbstractServerTransportListener;
import org.apache.dubbo.rpc.protocol.tri.h12.DefaultHttpMessageListener;
import org.apache.dubbo.rpc.protocol.tri.h12.HttpMessageListener;
import org.apache.dubbo.rpc.protocol.tri.h12.ServerCallListener;
import org.apache.dubbo.rpc.protocol.tri.h12.ServerStreamServerCallListener;
import org.apache.dubbo.rpc.protocol.tri.h12.UnaryServerCallListener;

public class DefaultHttp11ServerTransportListener
        extends AbstractServerTransportListener<RequestMetadata, HttpInputMessage>
        implements Http1ServerTransportListener {

    private final HttpChannel httpChannel;
    private Http1ServerChannelObserver responseObserver;

    public DefaultHttp11ServerTransportListener(HttpChannel httpChannel, URL url, FrameworkModel frameworkModel) {
        super(frameworkModel, url, httpChannel);
        // NettyHttp1Channel
        this.httpChannel = httpChannel;
        /**
         * Http1UnaryServerChannelObserver
         * StreamObserver is a common streaming API. It is an observer for receiving messages. Implementations are NOT required to be thread-safe.
         * 泛型 T 这里是 HttpChannel （NettyHttp1Channel）， StreamObserver 负责数据的处理（可以是请求数据也可以是响应数据）
         * responseObserver 负责处理数据的发送
         * */
        responseObserver = prepareResponseObserver(new Http1UnaryServerChannelObserver(httpChannel));
    }

    private Http1ServerChannelObserver prepareResponseObserver(Http1ServerChannelObserver responseObserver) {
        // 设置 ExceptionHandelr
        responseObserver.setExceptionCustomizer(getExceptionCustomizer());
        // 后续会在 org.apache.dubbo.rpc.protocol.tri.h12.AbstractServerTransportListener.doRoute 中进行设置
        RpcInvocationBuildContext context = getContext();
        // 最终会由 org.apache.dubbo.rpc.protocol.tri.h12.http1.DefaultHttp11ServerTransportListener.onMetadataCompletion 设置
        responseObserver.setResponseEncoder(context == null ? JsonCodec.INSTANCE : context.getHttpMessageEncoder());
        return responseObserver;
    }

    @Override
    protected HttpMessageListener buildHttpMessageListener() {
        // context 会在 org.apache.dubbo.rpc.protocol.tri.h12.AbstractServerTransportListener.doRoute 中填充
        RpcInvocationBuildContext context = getContext();
        // 构建 dubboInvoker 需要的 RpcInvocation
        // 1. 封装后边 dubboInvoker 调用时需要用到的方法元信息
        // 2. 将 http headers 全部添加到 RpcInvocation 中的 attachments
        // 3. RpcInvocationBuildContext 中设置的相关 attributes 转移到 RpcInvocation 中的 attributes
        // 4. HeaderFilter 过滤请求，headerFilter 用于跨域，认证（Authenticator），token 等过滤请求，不符合直接抛出异常
        RpcInvocation rpcInvocation = buildRpcInvocation(context);
        // 主要负责进行 Rpc 的调用(rpc 参数值已经 decode 完毕)
        // AutoCompleteUnaryServerCallListener
        ServerCallListener serverCallListener =
                startListener(rpcInvocation, context.getMethodDescriptor(), context.getInvoker());
        DefaultListeningDecoder listeningDecoder = new DefaultListeningDecoder(
                context.getHttpMessageDecoder(), context.getMethodMetadata().getActualRequestTypes());
        listeningDecoder.setListener(serverCallListener::onMessage);
        // DefaultListeningDecoder 封装了 HttpMessageDecoder , 方法的请求参数类型。以及 serverCallListener::onMessage 回调函数
        return new DefaultHttpMessageListener(listeningDecoder);
    }

    private ServerCallListener startListener(
            RpcInvocation invocation, MethodDescriptor methodDescriptor, Invoker<?> invoker) {
        switch (methodDescriptor.getRpcType()) {
            case UNARY:
                return new AutoCompleteUnaryServerCallListener(invocation, invoker, responseObserver);
            case SERVER_STREAM:
                responseObserver = prepareResponseObserver(new Http1SseServerChannelObserver(httpChannel));
                responseObserver.addHeadersCustomizer((hs, t) ->
                        hs.set(HttpHeaderNames.CONTENT_TYPE.getKey(), MediaType.TEXT_EVENT_STREAM.getName()));
                return new AutoCompleteServerStreamServerCallListener(invocation, invoker, responseObserver);
            default:
                throw new UnsupportedOperationException("HTTP1.x only support unary and server-stream");
        }
    }

    @Override
    protected void onMetadataCompletion(RequestMetadata metadata) {
        responseObserver.setResponseEncoder(getContext().getHttpMessageEncoder());
    }

    @Override
    protected void onError(Throwable throwable) {
        responseObserver.onError(throwable);
    }

    @Override
    protected void initializeAltSvc(URL url) {
        String protocolId = Http3Exchanger.isEnabled(url) ? "h3" : "h2";
        String value = protocolId + "=\":" + url.getParameter(Constants.BIND_PORT_KEY, url.getPort()) + '"';
        responseObserver.addHeadersCustomizer((hs, t) -> hs.set(HttpHeaderNames.ALT_SVC.getKey(), value));
    }

    private static final class AutoCompleteUnaryServerCallListener extends UnaryServerCallListener {

        AutoCompleteUnaryServerCallListener(
                RpcInvocation invocation, Invoker<?> invoker, StreamObserver<Object> responseObserver) {
            super(invocation, invoker, responseObserver);
        }

        @Override
        public void onMessage(Object message) {
            // message 为  Object[] decode ， 请求的方法参数
            // 向 RpcInvocation 中设置调用参数值 invocation.setArguments
            super.onMessage(message);
            // invoke 后端的 dubboInvoker
            onComplete();
        }
    }

    private static final class AutoCompleteServerStreamServerCallListener extends ServerStreamServerCallListener {

        AutoCompleteServerStreamServerCallListener(
                RpcInvocation invocation, Invoker<?> invoker, StreamObserver<Object> responseObserver) {
            super(invocation, invoker, responseObserver);
        }

        @Override
        public void onMessage(Object message) {
            super.onMessage(message);
            onComplete();
        }
    }
}
