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
package org.apache.dubbo.remoting.transport.netty4;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.transport.netty4.SslHandlerInitializer.HandshakeCompletionEvent;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleStateEvent;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NettyServerHandler.
 */
@io.netty.channel.ChannelHandler.Sharable
public class NettyServerHandler extends ChannelDuplexHandler {
    private static final Logger logger = LoggerFactory.getLogger(NettyServerHandler.class);
    /**
     * the cache for alive worker channel.
     * <ip:port, dubbo channel>
     */
    private final Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>();

    private final URL url;

    // NettyServer
    // Dubbo 层面的 pipeline

    // NettyServer 本身就是一个 ChannelHandler,位于整个 dubbo pipeline 的第一个
    private final ChannelHandler handler;

    public NettyServerHandler(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        // Dubbo 层面的 pipeline
        this.handler = handler;
    }

    public Map<String, Channel> getChannels() {
        return channels;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 连接建立之后，将 Netty native Channel 转换为 Dubbo Channel ，就是这里的 NettyChannel（dubbo实现，封装 nativeChannel）
        // 并建立 netty native channel 到 DubboChannel 之间的映射
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        if (channel != null) {
            // key:ip:port value: NettyChannel
            channels.put(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()), channel);
        }
        // org.apache.dubbo.remoting.transport.AbstractServer.connected
        // 从 NettyServer 相关回调方法开始
        handler.connected(channel);

        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getRemoteAddress() + " -> " + channel.getLocalAddress() + " is established.");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            channels.remove(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()));
            // org.apache.dubbo.remoting.transport.AbstractServer.disconnected
            // 从 NettyServer 相关回调方法开始
            handler.disconnected(channel);
        } finally {
            NettyChannel.removeChannel(ctx.channel());
        }

        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getRemoteAddress() + " -> " + channel.getLocalAddress() + " is disconnected.");
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 通过 native channel 映射获取 dubbo channel
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        // org.apache.dubbo.remoting.transport.AbstractPeer.received (开始走后面的 dubbo pipeline)
        handler.received(channel, msg);
    }

    // 由这里 org.apache.dubbo.remoting.transport.netty4.NettyChannel.send 发起调用
    // 当 msg 被 flush 到 socket 之后, promise 会被通知
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // 从当前 ChannelHandler 开始沿着 pipeline 向前传播 write 事件（异步）
        super.write(ctx, msg, promise);
        // 当 IO 线程就 msg 写入到 ChannelWriteBuffer 中之后，就会执行这里
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        // IO 线程执行, 主要为了执行 HeartbeatHandler ，其余 handler 的 sent 方法均为空实现
        // 注意 sent 方法的触发时机只是 msg 被写入到了 ChannelWriteBuffer（此时还未被 flush）
        // promise 可以获取 flush 的结果通知

        /**
         * 在 dubbo pipeline 的 sent 链路中，响应 sent 回调的 handler 只有两个，其余的都是空实现
         *
         * 1. HeartbeatHandler , 用于向 NettyChannel中更新 writeTimestamp
         * org.apache.dubbo.remoting.exchange.support.header.HeartbeatHandler#sent(org.apache.dubbo.remoting.Channel, java.lang.Object)
         *
         * 2. HeaderExchangeChannel , 用于设置发送 request future 的 sent 时间戳
         * sent 语义表示该 request 已经发送正在等待响应 （但其实 netty 现在只是将 request 刚刚写入 channelWriteBuffer 中）
         * org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeHandler#sent(org.apache.dubbo.remoting.Channel, java.lang.Object)
         * org.apache.dubbo.remoting.exchange.support.DefaultFuture#sent
         * */
        handler.sent(channel, msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // server will close channel when server don't receive any heartbeat from client util timeout.
        // 这里只是处理空闲事件，一旦空闲 server 就关闭 channel
        if (evt instanceof IdleStateEvent) {
            NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
            try {
                logger.info("IdleStateEvent triggered, close channel " + channel);
                channel.close();
            } finally {
                NettyChannel.removeChannelIfDisconnected(ctx.channel());
            }
        }
        // dubbo pipeline 中未实现响应 userEventTriggered 事件的方法
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            handler.caught(channel, cause);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    public void handshakeCompleted(HandshakeCompletionEvent evt) {
        // TODO
    }
}
