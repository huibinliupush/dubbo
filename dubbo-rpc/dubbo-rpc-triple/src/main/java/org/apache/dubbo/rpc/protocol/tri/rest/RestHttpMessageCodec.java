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
package org.apache.dubbo.rpc.protocol.tri.rest;

import org.apache.dubbo.common.io.StreamUtils;
import org.apache.dubbo.remoting.http12.HttpRequest;
import org.apache.dubbo.remoting.http12.HttpResponse;
import org.apache.dubbo.remoting.http12.exception.DecodeException;
import org.apache.dubbo.remoting.http12.exception.EncodeException;
import org.apache.dubbo.remoting.http12.exception.HttpStatusException;
import org.apache.dubbo.remoting.http12.message.HttpMessageDecoder;
import org.apache.dubbo.remoting.http12.message.HttpMessageEncoder;
import org.apache.dubbo.remoting.http12.message.MediaType;
import org.apache.dubbo.rpc.protocol.tri.rest.argument.ArgumentResolver;
import org.apache.dubbo.rpc.protocol.tri.rest.argument.TypeConverter;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ParameterMeta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Optional;

public final class RestHttpMessageCodec implements HttpMessageDecoder, HttpMessageEncoder {

    private static final Object[] EMPTY_ARGS = new Object[0];
    // 统一由 org.apache.dubbo.rpc.protocol.tri.rest.mapping.RestRequestHandlerMapping.getRequestHandler 设置
    private final HttpRequest request;
    private final HttpResponse response;
    private final ParameterMeta[] parameters;
    private final ArgumentResolver argumentResolver;
    private final TypeConverter typeConverter;
    // json 对应 JsonPbCodec
    // 这里会根据 mediaType 来设置对应的 encoder
    // see : org.apache.dubbo.remoting.http12.message.codec.CodecUtils.determineHttpMessageEncoder(org.apache.dubbo.common.URL, java.lang.String)

    // 根据我们指定的 produces = MediaType.TEXT_PLAIN_VALUE 不同，这里的 messageEncoder 也不同
    // MediaType.TEXT_PLAIN_VALUE 对应的是 PlainTextCodec
    private final HttpMessageEncoder messageEncoder;
    private final Charset charset;

    public RestHttpMessageCodec(
            HttpRequest request,
            HttpResponse response,
            ParameterMeta[] parameters,
            ArgumentResolver argumentResolver,
            TypeConverter typeConverter,
            HttpMessageEncoder messageEncoder) {
        this.request = request;
        this.response = response;
        this.parameters = parameters;
        this.argumentResolver = argumentResolver;
        this.typeConverter = typeConverter;
        // org.apache.dubbo.rpc.protocol.tri.rest.mapping.RestRequestHandlerMapping.getRequestHandler
        // org.apache.dubbo.remoting.http12.message.codec.CodecUtils.determineHttpMessageEncoder(org.apache.dubbo.common.URL, java.lang.String)
        this.messageEncoder = messageEncoder;
        charset = request.charsetOrDefault();
    }

    public HttpMessageEncoder getMessageEncoder() {
        return messageEncoder;
    }

    @Override
    public Object decode(InputStream inputStream, Class<?> targetType, Charset charset) throws DecodeException {
        return decode(inputStream, new Class<?>[] {targetType}, charset);
    }
    // RestHttpMessageCodec 根据方法参数中标注的 @RequestParam，@PathVariable，@RequestBody，@RequestHeader 注解
    // 通过对应的 ArgumentResolver 从 rest 请求中将方法参数 decode 出来
    @Override
    public Object[] decode(InputStream inputStream, Class<?>[] targetTypes, Charset charset) throws DecodeException {
        // 将传入的 ByteBufInputStream 转换为 ByteArrayInputStream（数组）
        request.setInputStream(decodeInputStream(inputStream));
        // 来自于 org.apache.dubbo.rpc.protocol.tri.rest.mapping.RestRequestHandlerMapping.getRequestHandler
        // 类型为 MethodParameterMeta
        ParameterMeta[] parameters = this.parameters; // 方法参数元数据
        int len = parameters.length;
        if (len == 0) {
            return EMPTY_ARGS;
        }
        // 存储 decode 出来的方法参数
        Object[] args = new Object[len];
        for (int i = 0; i < len; i++) {
            // CompositeArgumentResolver 封装各种 spring mvc 注解的 Resolver，比如，@RequestParam，@PathVariable，@RequestBody，@RequestHeade
            // 用来指示从 http request 的哪个地方获取请求参数
            // 根据方法参数上标注的 spring mvc 注解，解析方法参数（不同的注解不同的解析方式）
            args[i] = argumentResolver.resolve(parameters[i], request, response);
        }
        return args;
    }

    @Override
    public void encode(OutputStream os, Object data, Charset charset) throws EncodeException {
        encode(os, data);
    }

    private InputStream decodeInputStream(InputStream is) {
        if (is.getClass() == ByteArrayInputStream.class) {
            return is;
        }
        try {
            byte[] bytes = new byte[is.available()];
            is.read(bytes);
            return new ByteArrayInputStream(bytes);
        } catch (IOException e) {
            throw new DecodeException(e);
        }
    }

    @Override
    public void encode(OutputStream os, Object data) throws EncodeException {
        if (data != null) {
            Class<?> type = data.getClass();
            if (type == Optional.class) {
                encode(os, ((Optional<?>) data).orElse(null));
                return;
            }
            try {
                if (type == byte[].class) {
                    os.write((byte[]) data);
                    return;
                }
                if (type == ByteArrayOutputStream.class) {
                    ((ByteArrayOutputStream) data).writeTo(os);
                    return;
                }
                if (data instanceof InputStream) {
                    try (InputStream is = (InputStream) data) {
                        StreamUtils.copy(is, os);
                    }
                    return;
                }
                if (messageEncoder.mediaType().isPureText() && type != String.class) {
                    data = typeConverter.convert(data, String.class);
                }
            } catch (HttpStatusException e) {
                throw e;
            } catch (Exception e) {
                throw new EncodeException(e);
            }
        }
        // JsonPbCodec encode 将 data 转换为 json, 然后将 json byte 写入到 os 中
        // org.apache.dubbo.remoting.http12.message.codec.JsonCodec.encode(java.io.OutputStream, java.lang.Object, java.nio.charset.Charset)

        // 根据我们指定的 produces = MediaType.TEXT_PLAIN_VALUE 不同，这里的 messageEncoder 也不同
        // MediaType.TEXT_PLAIN_VALUE 对应的是 PlainTextCodec （直接写入文本串 bytes）
        messageEncoder.encode(os, data, charset);
    }

    @Override
    public MediaType mediaType() {
        return messageEncoder.mediaType();
    }

    @Override
    public String contentType() {
        String contentType = response.contentType();
        return contentType == null ? messageEncoder.contentType() : contentType;
    }
}
