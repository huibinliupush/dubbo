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
package org.apache.dubbo.remoting.http12;

import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.utils.JsonUtils;
import org.apache.dubbo.remoting.http12.exception.HttpStatusException;
import org.apache.dubbo.remoting.http12.message.HttpMessageEncoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.apache.dubbo.common.constants.LoggerCodeConstants.INTERNAL_ERROR;
import static org.apache.dubbo.common.logger.LoggerFactory.getErrorTypeAwareLogger;

public abstract class AbstractServerHttpChannelObserver<H extends HttpChannel> implements ServerHttpChannelObserver<H> {

    private static final ErrorTypeAwareLogger LOGGER = getErrorTypeAwareLogger(AbstractServerHttpChannelObserver.class);
    // NettyHttp1Channel
    private final H httpChannel;
    // 用于设置响应 headers
    private List<BiConsumer<HttpHeaders, Throwable>> headersCustomizers;
    // 比如响应 chunk 的时候，每个 chunk 都会有一个 header （trailers）用于指示 chunk 的大小
    private List<BiConsumer<HttpHeaders, Throwable>> trailersCustomizers;
    // 由 org.apache.dubbo.rpc.protocol.tri.h12.http1.DefaultHttp11ServerTransportListener.prepareResponseObserver 进行设置
    // 当 rpc 请求发生异常的时候就会回调这里
    // 异常的处理 org.apache.dubbo.rpc.protocol.tri.h12.CompositeExceptionHandler.handle
    // 具体负责处理异常的 handler see: org.apache.dubbo.rpc.protocol.tri.h12.CompositeExceptionHandler.exceptionHandlers
    // 由 ExceptionHandler 的 spi 定义, 泛型为支持处理的异常类型
    // org.apache.dubbo.rpc.protocol.tri.h12.CompositeExceptionHandler.cache 按照异常类型聚合对应的 ExceptionHandlers
    private Function<Throwable, ?> exceptionCustomizer;
    // JsonCodec
    // 由 org.apache.dubbo.rpc.protocol.tri.h12.http1.DefaultHttp11ServerTransportListener.onMetadataCompletion 设置
    private HttpMessageEncoder responseEncoder;

    private boolean headerSent;

    private boolean completed;

    private boolean closed;

    protected AbstractServerHttpChannelObserver(H httpChannel) {
        // NettyHttp1Channel
        this.httpChannel = httpChannel;
    }

    @Override
    public H getHttpChannel() {
        return httpChannel;
    }

    @Override
    public void addHeadersCustomizer(BiConsumer<HttpHeaders, Throwable> headersCustomizer) {
        if (headersCustomizers == null) {
            headersCustomizers = new ArrayList<>();
        }
        // 存储用于配置 http header 的 BiConsumer
        // org.apache.dubbo.rpc.protocol.tri.h12.http1.DefaultHttp11ServerTransportListener.initializeAltSvc
        headersCustomizers.add(headersCustomizer);
    }

    @Override
    public void addTrailersCustomizer(BiConsumer<HttpHeaders, Throwable> trailersCustomizer) {
        if (trailersCustomizers == null) {
            trailersCustomizers = new ArrayList<>();
        }
        trailersCustomizers.add(trailersCustomizer);
    }

    @Override
    public void setExceptionCustomizer(Function<Throwable, ?> exceptionCustomizer) {
        this.exceptionCustomizer = exceptionCustomizer;
    }

    public HttpMessageEncoder getResponseEncoder() {
        return responseEncoder;
    }

    public void setResponseEncoder(HttpMessageEncoder responseEncoder) {
        this.responseEncoder = responseEncoder;
    }

    @Override
    public final void onNext(Object data) {
        if (closed) {
            return;
        }
        try {
            // data 为 dubbo 方法返回的响应数据
            doOnNext(data);
        } catch (Throwable t) {
            LOGGER.warn(INTERNAL_ERROR, "", "", "Error while doOnNext", t);
            Throwable throwable = t;
            try {
                doOnError(throwable);
            } catch (Throwable t1) {
                LOGGER.warn(INTERNAL_ERROR, "", "", "Error while doOnError, original error: " + throwable, t1);
                throwable = t1;
            }
            onCompleted(throwable);
        }
    }

    @Override
    public final void onError(Throwable throwable) {
        if (closed) {
            return;
        }
        try {
            throwable = customizeError(throwable);
            if (throwable == null) {
                return;
            }
        } catch (Throwable t) {
            LOGGER.warn(INTERNAL_ERROR, "", "", "Error while handleError, original error: " + throwable, t);
            throwable = t;
        }

        try {
            doOnError(throwable);
        } catch (Throwable t) {
            LOGGER.warn(INTERNAL_ERROR, "", "", "Error while doOnError, original error: " + throwable, t);
            throwable = t;
        }
        onCompleted(throwable);
    }

    @Override
    public final void onCompleted() {
        if (closed) {
            return;
        }
        onCompleted(null);
    }
    // Http1UnaryServerChannelObserver
    protected void doOnNext(Object data) throws Throwable {
        // 获取响应码，如果 dubbo 返回返回类型为 HttpResult，那么就直接获取里面的 status
        // 如果返回类型为普通类型，直接设置 200
        int statusCode = resolveStatusCode(data);
        if (!headerSent) {
            sendMetadata(buildMetadata(statusCode, data, null, HttpOutputMessage.EMPTY_MESSAGE));
        }
        sendMessage(buildMessage(statusCode, data));
    }

    protected final int resolveStatusCode(Object data) {
        if (data instanceof HttpResult) {
            int status = ((HttpResult<?>) data).getStatus();
            if (status >= 100) {
                return status;
            }
        }
        return HttpStatus.OK.getCode();
    }

    protected final HttpMetadata buildMetadata(
            int statusCode, Object data, Throwable throwable, HttpOutputMessage message) {
        // 创建一个空的 Http1Metadata，后面主要用它来封装响应 headers
        HttpMetadata metadata = encodeHttpMetadata(message == null);
        HttpHeaders headers = metadata.headers();
        // 向 headers 中填充 :status
        headers.set(HttpHeaderNames.STATUS.getKey(), HttpUtils.toStatusString(statusCode));
        if (message != null) {
            // 填充 content-type
            headers.set(HttpHeaderNames.CONTENT_TYPE.getKey(), responseEncoder.contentType());
        }
        if (data instanceof HttpResult) {
            HttpResult<?> result = (HttpResult<?>) data;
            if (result.getHeaders() != null) {
                headers.set(result.getHeaders());
            }
        }
        // 执行 headersCustomizers， 设置响应 headers
        customizeHeaders(headers, throwable, message);
        return metadata;
    }

    protected abstract HttpMetadata encodeHttpMetadata(boolean endStream);

    protected void customizeHeaders(HttpHeaders headers, Throwable throwable, HttpOutputMessage message) {
        // 由 org.apache.dubbo.rpc.protocol.tri.h12.http1.DefaultHttp11ServerTransportListener.initializeAltSvc 设置
        List<BiConsumer<HttpHeaders, Throwable>> headersCustomizers = this.headersCustomizers;
        if (headersCustomizers != null) {
            for (int i = 0, size = headersCustomizers.size(); i < size; i++) {
                headersCustomizers.get(i).accept(headers, throwable);
            }
        }
    }

    protected final void sendMetadata(HttpMetadata metadata) {
        getHttpChannel().writeHeader(metadata);
        headerSent = true;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Http response headers sent: " + metadata.headers());
        }
    }

    protected final HttpOutputMessage buildMessage(int statusCode, Object data) throws Throwable {
        if (statusCode < 200 || statusCode == 204 || statusCode == 304) {
            return null;
        }
        if (data instanceof HttpResult) {
            data = ((HttpResult<?>) data).getBody();
        }
        if (data == null && statusCode != 200) {
            return null;
        }

        if (LOGGER.isDebugEnabled()) {
            try {
                String text;
                if (data instanceof byte[]) {
                    text = new String((byte[]) data, StandardCharsets.UTF_8);
                } else {
                    text = JsonUtils.toJson(data);
                }
                LOGGER.debug("Http response body sent: '{}' by [{}]", text, httpChannel);
            } catch (Throwable ignored) {
            }
        }
        // 创建一个空的 HttpOutputMessage 里面的 outputStream 此时为空
        HttpOutputMessage message = encodeHttpOutputMessage(data);
        try {
            // 空实现
            preOutputMessage(message);
            // 将 data 写入到 HttpOutputMessage 的 outputStream 中
            // JsonPbCodec encode 将 data 转换为 json, 然后将 json byte 写入到 os 中
            responseEncoder.encode(message.getBody(), data);
        } catch (Throwable t) {
            message.close();
            throw t;
        }
        return message;
    }

    protected HttpOutputMessage encodeHttpOutputMessage(Object data) {
        return getHttpChannel().newOutputMessage();
    }

    protected final void sendMessage(HttpOutputMessage message) throws Throwable {
        if (message == null) {
            return;
        }
        getHttpChannel().writeMessage(message);
        postOutputMessage(message);
    }

    protected void preOutputMessage(HttpOutputMessage message) throws Throwable {}

    protected void postOutputMessage(HttpOutputMessage message) throws Throwable {}

    protected Throwable customizeError(Throwable throwable) {
        if (exceptionCustomizer == null) {
            return throwable;
        }
        Object result = exceptionCustomizer.apply(throwable);
        if (result == null) {
            return throwable;
        }
        if (result instanceof Throwable) {
            return (Throwable) result;
        }
        onNext(result);
        return null;
    }

    protected void doOnError(Throwable throwable) throws Throwable {
        int statusCode = resolveErrorStatusCode(throwable);
        Object data = buildErrorResponse(statusCode, throwable);
        if (!headerSent) {
            sendMetadata(buildMetadata(statusCode, data, throwable, HttpOutputMessage.EMPTY_MESSAGE));
        }
        sendMessage(buildMessage(statusCode, data));
    }

    protected final int resolveErrorStatusCode(Throwable throwable) {
        if (throwable == null) {
            return HttpStatus.OK.getCode();
        }
        if (throwable instanceof HttpStatusException) {
            return ((HttpStatusException) throwable).getStatusCode();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.getCode();
    }

    protected final ErrorResponse buildErrorResponse(int statusCode, Throwable throwable) {
        ErrorResponse errorResponse = new ErrorResponse();
        errorResponse.setStatus(HttpUtils.toStatusString(statusCode));
        if (throwable instanceof HttpStatusException) {
            errorResponse.setMessage(((HttpStatusException) throwable).getDisplayMessage());
        } else {
            errorResponse.setMessage(getDisplayMessage(throwable));
        }
        return errorResponse;
    }

    protected String getDisplayMessage(Throwable throwable) {
        return "Internal Server Error";
    }

    protected void onCompleted(Throwable throwable) {
        if (completed) {
            return;
        }
        // Http1ServerChannelObserver
        doOnCompleted(throwable);
        completed = true;
    }
    // Http1ServerChannelObserver
    protected void doOnCompleted(Throwable throwable) {
        HttpMetadata trailerMetadata = encodeTrailers(throwable);
        if (trailerMetadata == null) {
            return;
        }
        HttpHeaders headers = trailerMetadata.headers();
        if (!headerSent) {
            headers.set(HttpHeaderNames.STATUS.getKey(), HttpUtils.toStatusString(resolveErrorStatusCode(throwable)));
            headers.set(HttpHeaderNames.CONTENT_TYPE.getKey(), getContentType());
        }
        customizeTrailers(headers, throwable);
        getHttpChannel().writeHeader(trailerMetadata);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Http response trailers sent: " + headers);
        }
    }

    protected HttpMetadata encodeTrailers(Throwable throwable) {
        return null;
    }

    protected String getContentType() {
        return responseEncoder.contentType();
    }

    protected void customizeTrailers(HttpHeaders headers, Throwable throwable) {
        List<BiConsumer<HttpHeaders, Throwable>> trailersCustomizers = this.trailersCustomizers;
        if (trailersCustomizers != null) {
            for (int i = 0, size = trailersCustomizers.size(); i < size; i++) {
                trailersCustomizers.get(i).accept(headers, throwable);
            }
        }
    }

    @Override
    public void close() {
        closed();
    }

    protected final void closed() {
        closed = true;
    }
}
