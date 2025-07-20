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
package org.apache.dubbo.remoting.http12.message;

import java.io.InputStream;

public class DefaultListeningDecoder implements ListeningDecoder {
    // RestHttpMessageCodec
    private final HttpMessageDecoder httpMessageDecoder;

    private final Class<?>[] targetTypes;
    // serverCallListener::onMessage
    // serverCallListener -> AutoCompleteUnaryServerCallListener
    // 主要负责进行 Rpc 的调用(rpc 参数值已经 decode 完毕)
    // AutoCompleteUnaryServerCallListener
    private Listener listener;
    // 由 org.apache.dubbo.rpc.protocol.tri.h12.http1.DefaultHttp11ServerTransportListener.buildHttpMessageListener 创建
    // 在 onMetaData 的处理中设置
    public DefaultListeningDecoder(HttpMessageDecoder httpMessageDecoder, Class<?>[] targetTypes) {
        this.httpMessageDecoder = httpMessageDecoder;
        this.targetTypes = targetTypes;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void decode(InputStream inputStream) {
        // RestHttpMessageCodec 根据方法参数中标注的 @RequestParam，@PathVariable，@RequestBody，@RequestHeader 注解
        // 通过对应的 ArgumentResolver 从 rest 请求中将方法参数 decode 出来
        Object[] decode = this.httpMessageDecoder.decode(inputStream, targetTypes);
        // 现在已经从 rest 请求中提取到了 dubboInvoker 执行的所有信息（invoker , RpcInvocation , 方法参数）
        // AutoCompleteUnaryServerCallListener 正是处理 rest 请求（由后面映射的 dubboInvoker 进行处理）
        this.listener.onMessage(decode);
    }

    @Override
    public void close() {
        this.listener.onClose();
    }
}
