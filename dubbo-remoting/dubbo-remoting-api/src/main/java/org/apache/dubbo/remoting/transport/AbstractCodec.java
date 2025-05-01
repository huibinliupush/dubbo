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
package org.apache.dubbo.remoting.transport;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.Constants;

import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;

/**
 * AbstractCodec
 */
public abstract class AbstractCodec implements Codec2 {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCodec.class);

    private static final String CLIENT_SIDE = "client";

    private static final String SERVER_SIDE = "server";
    // size 为 channel 要发送的字节数（被序列化之后的消息体）
    protected static void checkPayload(Channel channel, long size) throws IOException {
        // 默认 payload 为 8M
        int payload = Constants.DEFAULT_PAYLOAD;
        if (channel != null && channel.getUrl() != null) {
            // 也可以通过 payload 参数来进行配置
            payload = channel.getUrl().getParameter(Constants.PAYLOAD_KEY, Constants.DEFAULT_PAYLOAD);
        }
        // 发送的字节数太大超过 payload
        if (payload > 0 && size > payload) {
            ExceedPayloadLimitException e = new ExceedPayloadLimitException(
                "Data length too large: " + size + ", max payload: " + payload + ", channel: " + channel);
            logger.error(e);
            throw e;
        }
    }

    protected Serialization getSerialization(Channel channel) {
        return CodecSupport.getSerialization(channel.getUrl());
    }

    protected boolean isClientSide(Channel channel) {
        String side = (String)channel.getAttribute(SIDE_KEY);
        if (CLIENT_SIDE.equals(side)) {
            return true;
        } else if (SERVER_SIDE.equals(side)) {
            return false;
        } else {
            InetSocketAddress address = channel.getRemoteAddress();
            URL url = channel.getUrl();
            boolean isClient = url.getPort() == address.getPort()
                && NetUtils.filterLocalHost(url.getIp()).equals(
                NetUtils.filterLocalHost(address.getAddress()
                    .getHostAddress()));
            channel.setAttribute(SIDE_KEY, isClient ? CLIENT_SIDE
                : SERVER_SIDE);
            return isClient;
        }
    }

    protected boolean isServerSide(Channel channel) {
        return !isClientSide(channel);
    }

}
