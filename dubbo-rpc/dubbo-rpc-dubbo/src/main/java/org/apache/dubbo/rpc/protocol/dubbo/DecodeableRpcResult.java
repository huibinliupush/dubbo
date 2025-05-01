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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec;
import org.apache.dubbo.remoting.Decodeable;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;

public class DecodeableRpcResult extends AppResponse implements Codec, Decodeable {

    private static final Logger log = LoggerFactory.getLogger(DecodeableRpcResult.class);

    private Channel channel;

    private byte serializationType;

    private InputStream inputStream;

    private Response response;

    private Invocation invocation;

    private volatile boolean hasDecoded;

    public DecodeableRpcResult(Channel channel, Response response, InputStream is, Invocation invocation, byte id) {
        Assert.notNull(channel, "channel == null");
        Assert.notNull(response, "response == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.response = response;
        this.inputStream = is;
        this.invocation = invocation;
        this.serializationType = id;
    }

    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {
        if (log.isDebugEnabled()) {
            Thread thread = Thread.currentThread();
            log.debug("Decoding in thread -- [" + thread.getName() + "#" + thread.getId() + "]");
        }
        // 获取具体的序列化 KryoObjectInput
        ObjectInput in = CodecSupport.getSerialization(channel.getUrl(), serializationType)
                .deserialize(channel.getUrl(), input);

        /**
         * response 消息体编码
         * 1.返回值类型(byte)，标识从服务器端返回的值类型：
         *      返回空值：RESPONSE_NULL_VALUE 2   (远程方法返回值类型是 void)
         *      正常响应值： RESPONSE_VALUE 1
         *      异常：RESPONSE_WITH_EXCEPTION 0
         * 2.返回值：从服务端返回的响应bytes
         *
         * 3.Attachments
         *
         * org.apache.dubbo.rpc.protocol.dubbo.DubboCodec#encodeResponseData(org.apache.dubbo.remoting.Channel, org.apache.dubbo.common.serialize.ObjectOutput, java.lang.Object, java.lang.String)
         * */
        byte flag = in.readByte();
        switch (flag) {
            case DubboCodec.RESPONSE_NULL_VALUE: // 方法返回值为 void
                break;
            case DubboCodec.RESPONSE_VALUE:
                handleValue(in);
                break;
            case DubboCodec.RESPONSE_WITH_EXCEPTION:
                handleException(in);
                break;
            case DubboCodec.RESPONSE_NULL_VALUE_WITH_ATTACHMENTS:
                handleAttachment(in);
                break;
            case DubboCodec.RESPONSE_VALUE_WITH_ATTACHMENTS:
                handleValue(in);
                handleAttachment(in);
                break;
            case DubboCodec.RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS:
                handleException(in);
                handleAttachment(in);
                break;
            default:
                throw new IOException("Unknown result flag, expect '0' '1' '2' '3' '4' '5', but received: " + flag);
        }
        if (in instanceof Cleanable) {
            ((Cleanable) in).cleanup();
        }
        return this;
    }

    @Override
    public void decode() throws Exception {
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode rpc result failed: " + e.getMessage(), e);
                }
                response.setStatus(Response.CLIENT_ERROR);
                response.setErrorMessage(StringUtils.toString(e));
            } finally {
                hasDecoded = true;
            }
        }
    }

    private void handleValue(ObjectInput in) throws IOException {
        try {
            Type[] returnTypes;
            // 该阶段为 client 收到 server 端的 response , 解码 response
            // 这里的 invocation 是 client 发送 request 的时候设置的 RpcInvocation
            // see : org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeChannel.request(java.lang.Object, int, java.util.concurrent.ExecutorService)
            if (invocation instanceof RpcInvocation) {
                // see : org.apache.dubbo.common.utils.ReflectUtils.getReturnTypes
                returnTypes = ((RpcInvocation) invocation).getReturnTypes();
            } else {
                // see : org.apache.dubbo.common.utils.ReflectUtils.getReturnTypes
                // returnTypes[0]:方法的 returnType ， returnTypes[1]:returnType 中的泛型类
                // CompleteFutrue<String> , String
                returnTypes = RpcUtils.getReturnTypes(invocation);
            }
            // 在 client proxy 创建 RpcInvocation 的时候会生成 returnTypes
            // see : org.apache.dubbo.rpc.RpcInvocation.initParameterDesc
            Object value = null;
            if (ArrayUtils.isEmpty(returnTypes)) {
                // This almost never happens?
                value = in.readObject();
            } else if (returnTypes.length == 1) {
                // 将返回值反序列化为具体的类型
                value = in.readObject((Class<?>) returnTypes[0]);
            } else {
                // 对于方法原始返回类型为 CompleteFuture<String> 来说
                // 这里的真实 returnType[0] 是 string
                value = in.readObject((Class<?>) returnTypes[0], returnTypes[1]);
            }
            setValue(value);
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void handleException(ObjectInput in) throws IOException {
        try {
            setException(in.readThrowable());
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void handleAttachment(ObjectInput in) throws IOException {
        try {
            setObjectAttachments(in.readAttachments());
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void rethrow(Exception e) throws IOException {
        throw new IOException(StringUtils.toString("Read response data failed.", e));
    }
}
