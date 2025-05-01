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
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec;
import org.apache.dubbo.remoting.Decodeable;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.apache.dubbo.common.URL.buildKey;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.decodeInvocationArgument;

public class DecodeableRpcInvocation extends RpcInvocation implements Codec, Decodeable {

    private static final Logger log = LoggerFactory.getLogger(DecodeableRpcInvocation.class);

    private Channel channel;

    private byte serializationType;

    private InputStream inputStream;

    private Request request;

    private volatile boolean hasDecoded;

    public DecodeableRpcInvocation(Channel channel, Request request, InputStream is, byte id) {
        Assert.notNull(channel, "channel == null");
        Assert.notNull(request, "request == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.request = request;
        this.inputStream = is;
        this.serializationType = id;
    }

    @Override
    public void decode() throws Exception {
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode rpc invocation failed: " + e.getMessage(), e);
                }
                // 解码中出现错误就 broken
                request.setBroken(true);
                request.setData(e);
            } finally {
                hasDecoded = true;
            }
        }
    }

    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    // encode 过程
    // org.apache.dubbo.rpc.protocol.dubbo.DubboCodec.encodeRequestData(org.apache.dubbo.remoting.Channel, org.apache.dubbo.common.serialize.ObjectOutput, java.lang.Object, java.lang.String)
    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {
        ObjectInput in = CodecSupport.getSerialization(channel.getUrl(), serializationType)
                .deserialize(channel.getUrl(), input);
        // 1. Dubbo version
        String dubboVersion = in.readUTF();
        request.setVersion(dubboVersion);
        setAttachment(DUBBO_VERSION_KEY, dubboVersion);
        // 2. Service name
        String path = in.readUTF();
        setAttachment(PATH_KEY, path);
        // 3. Service version
        setAttachment(VERSION_KEY, in.readUTF());
        // 4. Method name
        setMethodName(in.readUTF());
        // 5. Method parameter types Desc
        String desc = in.readUTF();
        setParameterTypesDesc(desc);

        try {
            // 存放解码之后的参数
            Object[] args = DubboCodec.EMPTY_OBJECT_ARRAY;
            // 存放解码之后的参数类型
            Class<?>[] pts = DubboCodec.EMPTY_CLASS_ARRAY;
            if (desc.length() > 0) {
//                if (RpcUtils.isGenericCall(path, getMethodName()) || RpcUtils.isEcho(path, getMethodName())) {
//                    pts = ReflectUtils.desc2classArray(desc);
//                } else {
                // 获取 service 的元数据
                ServiceRepository repository = ApplicationModel.getServiceRepository();
                ServiceDescriptor serviceDescriptor = repository.lookupService(path);
                if (serviceDescriptor != null) {
                    MethodDescriptor methodDescriptor = serviceDescriptor.getMethod(getMethodName(), desc);
                    if (methodDescriptor != null) {
                        // 参数类型
                        pts = methodDescriptor.getParameterClasses();
                        // 返回类型
                        this.setReturnTypes(methodDescriptor.getReturnTypes());
                    }
                }
                if (pts == DubboCodec.EMPTY_CLASS_ARRAY) {
                    if (!RpcUtils.isGenericCall(path, getMethodName()) && !RpcUtils.isEcho(path, getMethodName())) {
                        throw new IllegalArgumentException("Service not found:" + path + ", " + getMethodName());
                    }
                    pts = ReflectUtils.desc2classArray(desc);
                }
//                }
                // 6. Method arguments(解码 method 参数)
                args = new Object[pts.length];
                for (int i = 0; i < args.length; i++) {
                    try {
                        // 按照参数类型，反序列化参数
                        // 如果参数类型是一个 callbackService，这里读取出来的就是 null
                        // 因为在 客户端的 encode 阶段，序列化进去的就是一个 null (callback相关信息会序列化到 attachments)
                        args[i] = in.readObject(pts[i]);
                    } catch (Exception e) {
                        if (log.isWarnEnabled()) {
                            log.warn("Decode argument failed: " + e.getMessage(), e);
                        }
                    }
                }
            }
            setParameterTypes(pts);
            // 7. Attachments
            Map<String, Object> map = in.readAttachments();
            if (map != null && map.size() > 0) {
                Map<String, Object> attachment = getObjectAttachments();
                if (attachment == null) {
                    attachment = new HashMap<>();
                }
                attachment.putAll(map);
                setObjectAttachments(attachment);
            }

            //decode argument ,may be callback
            for (int i = 0; i < args.length; i++) {
                // 如果该参数是一个 callbackService,那么就会在 service 端本地创建一个 reference 引用
                // 把这个 callbackService 的 reference 引用设置到参数中
                // 这样一来，在 service 方法中用户拿到的 callbackService 就是一个动态代理了（reference）
                args[i] = decodeInvocationArgument(channel, this, pts, i, args[i]); // 获取 callback 的 proxy
            }

            setArguments(args);
            String targetServiceName = buildKey((String) getAttachment(PATH_KEY),
                    getAttachment(GROUP_KEY),
                    getAttachment(VERSION_KEY));
            setTargetServiceUniqueName(targetServiceName);
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read invocation data failed.", e));
        } finally {
            if (in instanceof Cleanable) {
                ((Cleanable) in).cleanup();
            }
        }
        return this;
    }

}
