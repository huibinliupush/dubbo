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
package org.apache.dubbo.remoting.exchange;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashMapUtils;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.api.connection.AbstractConnectionClient;
import org.apache.dubbo.remoting.api.pu.AbstractPortUnificationServer;
import org.apache.dubbo.remoting.api.pu.PortUnificationTransporter;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.constants.LoggerCodeConstants.PROTOCOL_ERROR_CLOSE_SERVER;

public class PortUnificationExchanger {

    private static final ErrorTypeAwareLogger log =
            LoggerFactory.getErrorTypeAwareLogger(PortUnificationExchanger.class);
    // url.getAddress() -> NettyPortUnificationServer
    // 一个端口对应一个 server
    private static final ConcurrentMap<String, RemotingServer> servers = new ConcurrentHashMap<>();

    public static RemotingServer bind(URL url, ChannelHandler handler) {
        ConcurrentHashMapUtils.computeIfAbsent(servers, url.getAddress(), addr -> {
            final AbstractPortUnificationServer server;
            try {
                // url : tri://192.168.2.101:50052/org.example.RestTestService?anyhost=true&application=dubbo-springboot-triple-rest-springmvc&background=false&bind.ip=192.168.2.101&bind.port=50052&deprecated=false&dubbo=2.0.2&dynamic=true&executor-management-mode=isolation&file-cache=true&generic=false&interface=org.example.RestTestService&methods=getHead,getMuchParam,getMuchVariable,getReg,patchById,postList,postUseConsumesUser,postUseParams&pid=7809&prefer.serialization=hessian2,fastjson2&qos.enable=false&register=false&release=3.3.4&side=provider&timestamp=1752387447880&triple.rest.enable.default.mapping=false&triple.verbose=true
                // handler -> DefaultPuHandler(空实现)
                server = getTransporter(url).bind(url, handler);
            } catch (RemotingException e) {
                throw new RuntimeException(e);
            }
            // server.bind();
            return server;
        });
        // 必须在这里添加 addSupportedProtocol，因为不同的协议这里都会用同一端口，也就是都对应一个 server
        // 所以只能在这里添加，不能在创建 server 的时候添加，server 只会创建一次
        // 不同的协议可以 bind 多次
        servers.computeIfPresent(url.getAddress(), (addr, server) -> {
            // see : org.apache.dubbo.remoting.transport.netty4.NettyPortUnificationServer.addSupportedProtocol
            ((AbstractPortUnificationServer) server).addSupportedProtocol(url, handler);
            return server;
        });
        return servers.get(url.getAddress());
    }

    public static AbstractConnectionClient connect(URL url, ChannelHandler handler) {
        final AbstractConnectionClient connectionClient;
        try {
            connectionClient = getTransporter(url).connect(url, handler);
        } catch (RemotingException e) {
            throw new RuntimeException(e);
        }
        return connectionClient;
    }

    public static void close() {
        final ArrayList<RemotingServer> toClose = new ArrayList<>(servers.values());
        servers.clear();
        for (RemotingServer server : toClose) {
            try {
                server.close();
            } catch (Throwable throwable) {
                log.error(PROTOCOL_ERROR_CLOSE_SERVER, "", "", "Close all port unification server failed", throwable);
            }
        }
    }

    // for test
    public static ConcurrentMap<String, RemotingServer> getServers() {
        return servers;
    }

    public static PortUnificationTransporter getTransporter(URL url) {
        return url.getOrDefaultFrameworkModel()
                .getExtensionLoader(PortUnificationTransporter.class)
                .getAdaptiveExtension();
    }
}
