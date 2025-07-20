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
package org.apache.dubbo.rpc.protocol.dubbo.pu;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.api.AbstractWireProtocol;
import org.apache.dubbo.remoting.api.pu.ChannelOperator;

import java.util.ArrayList;
import java.util.List;

@Activate
public class DubboWireProtocol extends AbstractWireProtocol {
    public DubboWireProtocol() {
        super(new DubboDetector());
    }

    @Override
    public void configServerProtocolHandler(URL url, ChannelOperator operator) {
        List<ChannelHandler> handlers = new ArrayList<>();
        // operator(for now nettyOperator)'s duties
        // 1. config codec2 for the protocol(load by extension loader)
        // 2. config handlers passed by wire protocol
        // ( for triple, some h2 netty handler and logic handler to handle connection;
        //   for dubbo, nothing, an empty handlers is used to trigger operator logic)
        // 3. config Dubbo Inner handler(for dubbo protocol, this handler handles connection)

        // dubbo 协议相关的 pipeline 具体在 org.apache.dubbo.remoting.transport.netty4.NettyConfigOperator.configChannelHandler
        // 中进行配置，都是通用的，所以统一由 NettyConfigOperator 进行配置（所有协议都会走 NettyConfigOperator 进行配置）
        // 不管什么协议，最终都是要由 dubboInvoker 来处理，协议只是一个传输数据的角色
        // 不同协议对应不同的 handlers ，目的是提取数据，所以协议不同这里的 handlers 也不同
        // 提取完数据之后，封装通用的 RpcInvocation，后面就直接走 dubbo 路线了
        // 而 dubbo 协议有自己的编解码，所以这里的 handler 就是 nothing
        operator.configChannelHandler(handlers);
    }
}
