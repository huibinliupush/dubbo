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

import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.remoting.http12.exception.HttpRequestTimeout;
import org.apache.dubbo.remoting.http12.h2.Http2CancelableStreamObserver;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.RpcServiceContext;
import org.apache.dubbo.rpc.protocol.tri.TripleConstants;
import org.apache.dubbo.rpc.protocol.tri.TripleHeaderEnum;

import java.net.InetSocketAddress;

import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_APPLICATION_KEY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.PROTOCOL_TIMEOUT_SERVER;
import static org.apache.dubbo.rpc.protocol.tri.TripleConstants.REMOTE_ADDRESS_KEY;

public abstract class AbstractServerCallListener implements ServerCallListener {

    private static final ErrorTypeAwareLogger LOGGER =
            LoggerFactory.getErrorTypeAwareLogger(AbstractServerCallListener.class);

    protected final RpcInvocation invocation;

    protected final Invoker<?> invoker;
    // 负责数据的处理
    // see : org.apache.dubbo.rpc.protocol.tri.h12.http1.DefaultHttp11ServerTransportListener.DefaultHttp11ServerTransportListener
    protected final StreamObserver<Object> responseObserver;

    public AbstractServerCallListener(
            RpcInvocation invocation, Invoker<?> invoker, StreamObserver<Object> responseObserver) {
        this.invocation = invocation;
        this.invoker = invoker;
        this.responseObserver = responseObserver;
    }

    public void invoke() {
        if (responseObserver instanceof Http2CancelableStreamObserver) {
            RpcContext.restoreCancellationContext(
                    ((Http2CancelableStreamObserver<Object>) responseObserver).getCancellationContext());
        }
        // 获取并填充 RpcContext
        RpcServiceContext serviceContext = RpcContext.getServiceContext();
        // 设置远端地址
        serviceContext.setRemoteAddress((InetSocketAddress) invocation.remove(REMOTE_ADDRESS_KEY));
        // 设置 consumer 应用名称
        String remoteApp = (String) invocation.remove(TripleHeaderEnum.CONSUMER_APP_NAME_KEY);
        if (remoteApp != null) {
            serviceContext.setRemoteApplicationName(remoteApp);
            invocation.setAttachmentIfAbsent(REMOTE_APPLICATION_KEY, remoteApp);
        }
        // 设置 request and  response
        if (serviceContext.getRequest() == null) {
            // 在路由的时候由 org.apache.dubbo.rpc.protocol.tri.route.DefaultRequestRouter.route 设置
            serviceContext.setRequest(invocation.get(TripleConstants.HTTP_REQUEST_KEY));
            serviceContext.setResponse(invocation.get(TripleConstants.HTTP_RESPONSE_KEY));
        }

        try {
            long stInMillis = System.currentTimeMillis();
            // 调用后端 invoker , 经过 filter 链 ，abstractProxyInvoker
            // AsyncRpcResult
            Result response = invoker.invoke(invocation);
            if (response.hasException()) {
                responseObserver.onError(response.getException());
                return;
            }
            response.whenCompleteWithContext((r, t) -> {
                if (responseObserver instanceof AttachmentHolder) {
                    ((AttachmentHolder) responseObserver).setResponseAttachments(response.getObjectAttachments());
                }
                if (t != null) {
                    responseObserver.onError(t);
                    return;
                }
                if (r.hasException()) {
                    responseObserver.onError(r.getException());
                    return;
                }
                // dubbo 调用耗时（包含 filter 链）
                long cost = System.currentTimeMillis() - stInMillis;
                // 获取超时时间
                Long timeout = (Long) invocation.get("timeout");
                if (timeout != null && timeout < cost) {
                    LOGGER.error(
                            PROTOCOL_TIMEOUT_SERVER,
                            "",
                            "",
                            String.format(
                                    "Invoke timeout at server side, ignored to send response. service=%s method=%s cost=%s",
                                    invocation.getTargetServiceUniqueName(), invocation.getMethodName(), cost));
                    HttpRequestTimeout serverSideTimeout = HttpRequestTimeout.serverSide();
                    // 发送超时信息
                    responseObserver.onError(serverSideTimeout);
                    return;
                }
                // 发送 http response
                onReturn(r.getValue());
            });
        } catch (Exception e) {
            responseObserver.onError(e);
        } finally {
            RpcContext.removeCancellationContext();
            RpcContext.removeContext();
        }
    }

    public abstract void onReturn(Object value);
}
