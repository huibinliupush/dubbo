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
package org.apache.dubbo.remoting.http12.message.codec;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.Configuration;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.http12.exception.UnsupportedMediaTypeException;
import org.apache.dubbo.remoting.http12.message.HttpMessageDecoder;
import org.apache.dubbo.remoting.http12.message.HttpMessageDecoderFactory;
import org.apache.dubbo.remoting.http12.message.HttpMessageEncoder;
import org.apache.dubbo.remoting.http12.message.HttpMessageEncoderFactory;
import org.apache.dubbo.rpc.Constants;
import org.apache.dubbo.rpc.model.FrameworkModel;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CodecUtils {

    private final FrameworkModel frameworkModel;
    // JsonPbCodecFactory, JsonCodecFactory,GrpcCompositeCodecFactory,YamlCodecFactory 等
    private final List<HttpMessageDecoderFactory> decoderFactories;
    // JsonPbCodecFactory, JsonCodecFactory,GrpcCompositeCodecFactory,YamlCodecFactory 等
    private final List<HttpMessageEncoderFactory> encoderFactories;
    // 不同的 mediaType 对应不同的 HttpMessageEncoderFactory
    private final Map<String, Optional<HttpMessageEncoderFactory>> encoderCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<HttpMessageDecoderFactory>> decoderCache = new ConcurrentHashMap<>();
    private Set<String> disallowedContentTypes = Collections.emptySet();

    public CodecUtils(FrameworkModel frameworkModel) {
        this.frameworkModel = frameworkModel;
        decoderFactories = frameworkModel.getActivateExtensions(HttpMessageDecoderFactory.class);
        encoderFactories = frameworkModel.getActivateExtensions(HttpMessageEncoderFactory.class);

        Configuration configuration = ConfigurationUtils.getGlobalConfiguration(frameworkModel.defaultApplication());
        String contentTypes = configuration.getString(Constants.H2_SETTINGS_DISALLOWED_CONTENT_TYPES, null);
        if (contentTypes != null) {
            disallowedContentTypes = new HashSet<>(StringUtils.tokenizeToList(contentTypes));
        }
    }

    public HttpMessageDecoder determineHttpMessageDecoder(URL url, String mediaType) {
        return determineHttpMessageDecoderFactory(mediaType)
                .orElseThrow(() -> new UnsupportedMediaTypeException(mediaType))
                .createCodec(url, frameworkModel, mediaType);
    }

    public HttpMessageDecoder determineHttpMessageDecoder(String mediaType) {
        return determineHttpMessageDecoder(null, mediaType);
    }
    // 根据 mediaType 获取对应的 HttpMessageEncoder
    public HttpMessageEncoder determineHttpMessageEncoder(URL url, String mediaType) {
        // 根据 mediaType 选取对应的 HttpMessageEncoderFactory
        // 比如 application/json 对应的是 JsonPbCodecFactory
        // 然后通过 JsonPbCodecFactory 来 createCodec -> JsonPbCodec
        return determineHttpMessageEncoderFactory(mediaType)
                .orElseThrow(() -> new UnsupportedMediaTypeException(mediaType))
                .createCodec(url, frameworkModel, mediaType);
    }

    public HttpMessageEncoder determineHttpMessageEncoder(String mediaType) {
        return determineHttpMessageEncoder(null, mediaType);
    }

    public Optional<HttpMessageDecoderFactory> determineHttpMessageDecoderFactory(String mediaType) {
        Assert.notNull(mediaType, "mediaType must not be null");
        return decoderCache.computeIfAbsent(mediaType, k -> {
            for (HttpMessageDecoderFactory factory : decoderFactories) {
                if (factory.supports(k)
                        && !disallowedContentTypes.contains(factory.mediaType().getName())) {
                    return Optional.of(factory);
                }
            }
            return Optional.empty();
        });
    }

    public Optional<HttpMessageEncoderFactory> determineHttpMessageEncoderFactory(String mediaType) {
        Assert.notNull(mediaType, "mediaType must not be null");
        return encoderCache.computeIfAbsent(mediaType, k -> {
            for (HttpMessageEncoderFactory factory : encoderFactories) {
                // 根据 mediaType 选取对应的 HttpMessageEncoderFactory
                // 比如 application/json 对应的是 JsonPbCodecFactory
                if (factory.supports(k)
                        && !disallowedContentTypes.contains(factory.mediaType().getName())) {
                    return Optional.of(factory);
                }
            }
            return Optional.empty();
        });
    }

    public List<HttpMessageDecoderFactory> getDecoderFactories() {
        return decoderFactories;
    }

    public List<HttpMessageEncoderFactory> getEncoderFactories() {
        return encoderFactories;
    }
}
