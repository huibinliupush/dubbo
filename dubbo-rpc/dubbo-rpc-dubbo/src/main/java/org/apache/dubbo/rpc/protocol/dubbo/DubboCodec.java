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

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.io.UnsafeByteArrayInputStream;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.codec.ExchangeCodec;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcInvocation;

import java.io.IOException;
import java.io.InputStream;

import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.encodeInvocationArgument;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DECODE_IN_IO_THREAD_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_DECODE_IN_IO_THREAD;

/**
 * Dubbo codec.
 */
public class DubboCodec extends ExchangeCodec {

    public static final String NAME = "dubbo";
    public static final String DUBBO_VERSION = Version.getProtocolVersion();
    public static final byte RESPONSE_WITH_EXCEPTION = 0;
    public static final byte RESPONSE_VALUE = 1;
    public static final byte RESPONSE_NULL_VALUE = 2;
    public static final byte RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS = 3;
    public static final byte RESPONSE_VALUE_WITH_ATTACHMENTS = 4;
    public static final byte RESPONSE_NULL_VALUE_WITH_ATTACHMENTS = 5;
    public static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
    public static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];
    private static final Logger log = LoggerFactory.getLogger(DubboCodec.class);

    @Override
    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        // header[2] 里边的信息 ：Req/Res (1 bit) , 2 Way (1 bit) , Event (1 bit) , Serialization ID (5 bit)
        byte flag = header[2];
        // 获取 Serialization ID
        byte proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id. 从 header[4] 开始大端读取 8 个字节
        long id = Bytes.bytes2long(header, 4);
        // 解码 response
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            if ((flag & FLAG_EVENT) != 0) {
                // 消息体为 org.apache.dubbo.common.constants.CommonConstants
                res.setEvent(true);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            try {
                // see : org.apache.dubbo.remoting.exchange.codec.ExchangeCodec.encodeResponse
                if (status == Response.OK) {
                    Object data;
                    if (res.isEvent()) {
                        // 根据序列化协议 id , 获取具体的序列化 KryoObjectInput
                        ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                        // readObject
                        data = decodeEventData(channel, in);
                    } else {
                        // 继承 AppResponse
                        DecodeableRpcResult result; // 待解码的消息体
                        // 是否要在 io 线程中执行 decode 消息体的操作 ？
                        if (channel.getUrl().getParameter(DECODE_IN_IO_THREAD_KEY, DEFAULT_DECODE_IN_IO_THREAD)) {
                            // channel 为 client 端接收相应的 channel
                            // res 为根据 buffer 中解码出来的 header 创建出来的 response
                            // is 为待解码的 buffer, 其中有待解码的消息体 reuslt —— appReponse 元数据
                            // getRequestData 为 client 发起请求时的 request 数据，存放在 requestFuture 中（现在是接收响应）
                            // proto 表示序列化算法
                            result = new DecodeableRpcResult(channel, res, is,
                                    (Invocation) getRequestData(id), proto);
                            // 在 io 线程中对消息体进行解码
                            result.decode();
                        } else {
                            // 在 dubbo 线程或者用户线程中对消息体进行解码
                            // see : org.apache.dubbo.remoting.transport.DecodeHandler.received
                            result = new DecodeableRpcResult(channel, res,
                                    new UnsafeByteArrayInputStream(readMessageData(is)),
                                    (Invocation) getRequestData(id), proto);
                        }
                        data = result;
                    }
                    // 设置待解码的消息体 AppResponse
                    res.setResult(data);
                } else {
                    // see : org.apache.dubbo.remoting.exchange.codec.ExchangeCodec.encodeResponse
                    // status != Response.OK 的时候只是会写入 ErrorMessage
                    ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode response failed: " + t.getMessage(), t);
                }
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(true);
            }
            try {
                Object data;
                if (req.isEvent()) {
                    ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                    // 事件 data 来自于 org.apache.dubbo.common.constants.CommonConstants
                    data = decodeEventData(channel, in);
                } else {
                    // 继承 RpcInvocation
                    // inv 在 decode 的过程中会设置 RpcInvocation 的相关属性
                    // 这样设计为了，消息体的 decode 操作可以灵活的安排在 io 线程或者 dubbo 线程中，也可以是用户线程
                    DecodeableRpcInvocation inv;
                    if (channel.getUrl().getParameter(DECODE_IN_IO_THREAD_KEY, DEFAULT_DECODE_IN_IO_THREAD)) {
                        inv = new DecodeableRpcInvocation(channel, req, is, proto);
                        inv.decode();
                    } else {
                        inv = new DecodeableRpcInvocation(channel, req,
                                new UnsafeByteArrayInputStream(readMessageData(is)), proto);
                    }
                    data = inv;
                }
                req.setData(data);
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode request failed: " + t.getMessage(), t);
                }
                // bad request
                req.setBroken(true);
                req.setData(t);
            }

            return req;
        }
    }

    private byte[] readMessageData(InputStream is) throws IOException {
        if (is.available() > 0) {
            byte[] result = new byte[is.available()];
            is.read(result);
            return result;
        }
        return new byte[]{};
    }

    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data, DUBBO_VERSION);
    }

    @Override
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(channel, out, data, DUBBO_VERSION);
    }
    // request 消息体编码
    // https://cn.dubbo.apache.org/zh-cn/overview/reference/protocols/tcp/
    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        RpcInvocation inv = (RpcInvocation) data;
        // 1. Dubbo version
        out.writeUTF(version);
        // https://github.com/apache/dubbo/issues/6138
        String serviceName = inv.getAttachment(INTERFACE_KEY);
        if (serviceName == null) {
            serviceName = inv.getAttachment(PATH_KEY);
        }
        // 2. Service name
        out.writeUTF(serviceName);
        // 3. Service version
        out.writeUTF(inv.getAttachment(VERSION_KEY));
        // 4. Method name
        out.writeUTF(inv.getMethodName());
        // 5. Method parameter types Desc
        out.writeUTF(inv.getParameterTypesDesc());
        // 6. Method arguments(编码 method 参数)
        Object[] args = inv.getArguments();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                // 主要处理 callback 参数，client 本地暴露 callback 服务（不进行序列化）但会将 callback 实例的 hashcode 设置到 Attachments 中
                // 如果参数是 callbackService ， 那么这里序列化进去的就是 null
                // 其他正常参数直接序列化进 out 中
                out.writeObject(encodeInvocationArgument(channel, inv, i));
            }
        }
        // 7. Attachments
        out.writeAttachments(inv.getObjectAttachments());
    }
    // response 消息体编码 , 这里是处理正常响应的
    // event 以及异常响应已经在 ExchangeCodec 中处理了，直接将 errorMessage 写入即可
    // https://cn.dubbo.apache.org/zh-cn/overview/reference/protocols/tcp/
    @Override
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        // AppResponse
        Result result = (Result) data;
        // currently, the version value in Response records the version of Request
        // 是否在响应消息体中带上 Attachments
        boolean attach = Version.isSupportResponseAttachment(version); // true
        Throwable th = result.getException();
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
         * 对应的解码流程 org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcResult#decode(org.apache.dubbo.remoting.Channel, java.io.InputStream)
         * */
        if (th == null) {
            // AppResponse
            // 如果方法返回类型为 void , 这里就是 null
            // org.apache.dubbo.rpc.proxy.AbstractProxyInvoker.invoke
            Object ret = result.getValue();
            if (ret == null) {
                out.writeByte(attach ? RESPONSE_NULL_VALUE_WITH_ATTACHMENTS : RESPONSE_NULL_VALUE);
            } else {
                out.writeByte(attach ? RESPONSE_VALUE_WITH_ATTACHMENTS : RESPONSE_VALUE);
                out.writeObject(ret);
            }
        } else {
            out.writeByte(attach ? RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS : RESPONSE_WITH_EXCEPTION);
            out.writeThrowable(th);
        }

        if (attach) {
            // returns current version of Response to consumer side.
            result.getObjectAttachments().put(DUBBO_VERSION_KEY, Version.getProtocolVersion());
            out.writeAttachments(result.getObjectAttachments());
        }
    }
}
