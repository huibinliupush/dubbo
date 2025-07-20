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

import org.apache.dubbo.remoting.http12.HttpChannel;
import org.apache.dubbo.remoting.http12.HttpHeaderNames;
import org.apache.dubbo.remoting.http12.HttpHeaders;
import org.apache.dubbo.remoting.http12.HttpOutputMessage;
import org.apache.dubbo.remoting.http12.h1.Http1ServerChannelObserver;
import org.apache.dubbo.rpc.protocol.tri.ExceptionUtils;
import org.apache.dubbo.rpc.protocol.tri.TripleProtocol;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import io.netty.buffer.ByteBufOutputStream;

public final class Http1UnaryServerChannelObserver extends Http1ServerChannelObserver {

    public Http1UnaryServerChannelObserver(HttpChannel httpChannel) {
        // NettyHttp1Channel
        super(httpChannel);
    }
    // 对于发送过程的处理转换 see : NettyHttp1Codec
    @Override
    protected void doOnNext(Object data) throws Throwable {
        // 获取响应码，如果 dubbo 返回返回类型为 HttpResult，那么就直接获取里面的 status
        // 如果返回类型为普通类型，直接设置 200
        int statusCode = resolveStatusCode(data);
        // 将 data 按照 mediaType 转换为对应格式的数据，然后写入到 HttpOutputMessage 中的 outStream 中
        HttpOutputMessage message = buildMessage(statusCode, data);
        // buildMetadata 主要用来构建响应 headers
        // sendMetadata 发送响应 headers
        sendMetadata(buildMetadata(statusCode, data, null, message));
        // 发送 body
        sendMessage(message);
    }

    @Override
    protected void doOnError(Throwable throwable) throws Throwable {
        int statusCode = resolveErrorStatusCode(throwable);
        Object data = buildErrorResponse(statusCode, throwable);
        HttpOutputMessage message = buildMessage(statusCode, data);
        sendMetadata(buildMetadata(statusCode, data, throwable, message));
        sendMessage(message);
    }

    @Override
    protected void customizeHeaders(HttpHeaders headers, Throwable throwable, HttpOutputMessage message) {
        super.customizeHeaders(headers, throwable, message);
        int contentLength = 0;
        if (message != null) {
            OutputStream body = message.getBody();
            if (body instanceof ByteBufOutputStream) {
                contentLength = ((ByteBufOutputStream) body).writtenBytes();
            } else if (body instanceof ByteArrayOutputStream) {
                contentLength = ((ByteArrayOutputStream) body).size();
            } else {
                throw new IllegalArgumentException("Unsupported body type: " + body.getClass());
            }
        }
        // 设置 content-length
        headers.set(HttpHeaderNames.CONTENT_LENGTH.getKey(), String.valueOf(contentLength));
    }

    @Override
    protected Throwable customizeError(Throwable throwable) {
        throwable = super.customizeError(throwable);
        if (throwable == null) {
            doOnCompleted(null);
        }
        return throwable;
    }

    @Override
    protected String getDisplayMessage(Throwable throwable) {
        return TripleProtocol.VERBOSE_ENABLED
                ? ExceptionUtils.buildVerboseMessage(throwable)
                : super.getDisplayMessage(throwable);
    }
}
