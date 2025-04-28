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
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleStateEvent;

import static org.apache.dubbo.common.constants.CommonConstants.HEARTBEAT_EVENT;

/**
 * NettyClientHandler
 */
@io.netty.channel.ChannelHandler.Sharable
public class NettyClientHandler extends ChannelDuplexHandler {
    private static final Logger logger = LoggerFactory.getLogger(NettyClientHandler.class);

    private final URL url;

    private final ChannelHandler handler;

    public NettyClientHandler(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }
    // connect 事件产生，Netty 会先通知 connect future ,然后触发 channelActive
    // connect future 的处理 see：
    // org.apache.dubbo.remoting.transport.netty4.NettyClient.doConnect
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 建立 netty native channel 与 dubbo channel 之间的映射
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        // 从 NettyClient 相关回调方法开始, NettyClient 是 dubbo pipine 中的第一个 handler
        handler.connected(channel);
        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getLocalAddress() + " -> " + channel.getRemoteAddress() + " is established.");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            // 从 NettyClient 相关回调方法开始
            handler.disconnected(channel);
        } finally {
            NettyChannel.removeChannel(ctx.channel());
        }

        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getLocalAddress() + " -> " + channel.getRemoteAddress() + " is disconnected.");
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        handler.received(channel, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
        final NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        final boolean isRequest = msg instanceof Request;

        // We add listeners to make sure our out bound event is correct.
        // If our out bound event has an error (in most cases the encoder fails),
        // we need to have the request return directly instead of blocking the invoke process.
        promise.addListener(future -> {
            if (future.isSuccess()) {
                // if our future is success, mark the future to sent.
                handler.sent(channel, msg);
                return;
            }
            // 客户端发送 request, netty 层面异常，但还是在本地，那么就返回用户一个 ErrorResponse
            Throwable t = future.cause();
            if (t != null && isRequest) {
                Request request = (Request) msg;
                Response response = buildErrorResponse(request, t);
                // 向用户返回错误响应
                handler.received(channel, response);
            }
        });
    }

    /**
     *  1. 当客户端读空闲（这里有问题）超过 heartbeat 的时间，就会在这里向服务端发送心跳 request （应该是读或写空闲就发送心跳）
     *     写空闲了，说明 client 很长时间没发送消息了，这时需要向 server 发送消息，否则 server idleTimeout 就关连接了
     *     ** ： 突然觉得这里只是读空闲也没问题，因为写空闲了（没有发送数据），自然就会读空闲（没有响应数据）
     *
     *  2. 客户端读空闲超过 heartbeat，同时也会检查 channel 是否连接，否则就断开重连
     *  3. 如果读空闲时间超过 idleTimeout(3 * hearbeat) , 那么客户单就断开重连，场景是 client 发出心跳，server 未响应
     *  org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeClient.startReconnectTask
     *
     *
     *  优化：其实这里完全可以省去 HeartbeatHandler 以及 HeaderExchangeClient 中的 startReconnectTask
     *  startReconnectTask 主要是每隔 heartbeat 间隔来检查一下 channel 是否 connect 这个已经在 userEventTriggered 方法中有了 removeChannelIfDisconnected
     *  另外会检查 client 发出的心跳 request 是否超时 —— 读空闲超过 idleTimeout(3 * heartbeat),如果空闲就断开重连
     *
     *  但其实  startReconnectTask 的功能完全可以在 userEventTriggered 中实现，因为 IdleStateHandler 中的读空闲时间设置的是 heartbeatInterval
     *  所以在经过 heartbeatInterval 间隔之后，会产生 IdleStateEvent 事件，这里直接发送心跳 request
     *
     *  那么心跳 request 超时如何检测呢 ？不依赖 startReconnectTask 的话，netty 实现的 IdleStateHandler 中，产生的 IdleStateEvent 是
     *  1 : IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT, 当第一次产生空闲事件时触发，这里我们可以实现原有逻辑 —— 发送心跳 request
     *  2 : IdleStateEvent.READER_IDLE_STATE_EVENT , 第一次空闲事件产生之后，持续产生空闲事件，也就是说在后面的时间里，仍然没有 server 的响应
 *          那么就断开重连，这里我们可以设计一个计数，如果连续产生两次 READER_IDLE_STATE_EVENT 事件就断开重连（考虑到客户端要进行重试）
     *
     *  HeartbeatHandler 还是不能省，因为它要处理接收心跳包的逻辑（心跳requst,response），但可以不记录相关 timestamp, 只实现 received 方法
*       org.apache.dubbo.remoting.exchange.support.header.HeartbeatHandler#received(org.apache.dubbo.remoting.Channel, java.lang.Object)
     *
     * */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // send heartbeat when read idle.
        // client 空闲则发送心跳，*** 注意 ***   心跳的发送是等空闲了才发送，而不是定时发送，因为平时正常的发送 request 也算是心跳了
        if (evt instanceof IdleStateEvent) {
            try {
                NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
                if (logger.isDebugEnabled()) {
                    logger.debug("IdleStateEvent triggered, send heartbeat to channel " + channel);
                }
                Request req = new Request();
                req.setVersion(Version.getProtocolVersion());
                req.setTwoWay(true);
                req.setEvent(HEARTBEAT_EVENT);
                channel.send(req);
            } finally {
                // 如果已经断连，则从缓存中删除，markActive = false
                NettyChannel.removeChannelIfDisconnected(ctx.channel());
                // 完整心跳方案还要再加上：org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeClient.startReconnectTask
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
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

    public void handshakeCompleted(SslHandlerInitializer.HandshakeCompletionEvent evt) {
        // TODO
    }

    /**
     * build a bad request's response
     *
     * @param request the request
     * @param t       the throwable. In most cases, serialization fails.
     * @return the response
     */
    private static Response buildErrorResponse(Request request, Throwable t) {
        Response response = new Response(request.getId(), request.getVersion());
        response.setStatus(Response.BAD_REQUEST);
        response.setErrorMessage(StringUtils.toString(t));
        return response;
    }
}
