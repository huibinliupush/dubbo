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
package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Request;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.unmodifiableCollection;
import static org.apache.dubbo.common.constants.CommonConstants.READONLY_EVENT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_CHECK_TICK;
import static org.apache.dubbo.remoting.Constants.LEAST_HEARTBEAT_DURATION;
import static org.apache.dubbo.remoting.Constants.TICKS_PER_WHEEL;
import static org.apache.dubbo.remoting.utils.UrlUtils.getHeartbeat;
import static org.apache.dubbo.remoting.utils.UrlUtils.getIdleTimeout;

/**
 * ExchangeServerImpl
 */
public class HeaderExchangeServer implements ExchangeServer {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    // NettyServer
    private final RemotingServer server;
    private AtomicBoolean closed = new AtomicBoolean(false);

    private static final HashedWheelTimer IDLE_CHECK_TIMER = new HashedWheelTimer(new NamedThreadFactory("dubbo-server-idleCheck", true), 1,
            TimeUnit.SECONDS, TICKS_PER_WHEEL);

    private CloseTimerTask closeTimerTask;

    public HeaderExchangeServer(RemotingServer server) {
        Assert.notNull(server, "server == null");
        // NettyServer
        this.server = server;
        // 空闲连接的关闭
        startIdleCheckTask(getUrl());
    }

    public RemotingServer getServer() {
        return server;
    }

    @Override
    public boolean isClosed() {
        return server.isClosed();
    }

    private boolean isRunning() {
        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {

            /**
             *  If there are any client connections,
             *  our server should be running.
             */

            if (channel.isConnected()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        doClose();
        server.close();
    }

    @Override
    public void close(final int timeout) {
        // 先设置 closing 标识 为 true , 再有连接进来，server 会直接断开
        startClose();
        if (timeout > 0) {
            final long max = (long) timeout;
            final long start = System.currentTimeMillis();
            /**
             * 这里就是 server 关闭的核心流程，在这之前，dubbo 首先会将进程的所有 provider 全部取消注册
             * 然后 consumer 收到取消注册事件，会销毁对应的 dubboInovker，关闭底层的客户端连接（由客户端主动关闭）
             *
             * 但是 consumer 收到注册中心的通知以及处理这个通知（销毁 invoker）是有延迟的，延迟主要看注册中心的通知是否及时
             * 所以很有可能是 server 要关闭了，但是 consumer 还没有收到注册中心的通知，依然还在用客户端连接发送消息
             *
             * 所以为了避免这个延迟的影响，在 server 关闭的时候会向所有的客户端连接发送 read only 事件，consumer 收到 read only 事件之后
             * 就只能接受来自 server 的响应，不能向 server 发送消息了。（consumer 对应的 dubboInvoker 状态变为不可用，负载均衡将不会选取）
             *
             * 这样一来虽然注册中心通知会有延迟，但是 consumer 收到了 read only ，在这段时间内不会向 server 继续发送消息
             *
             * 在服务向注册中心注销的时候，也会马上等待 10s, see : org.apache.dubbo.registry.integration.RegistryProtocol.ExporterChangeableWrapper#unexport()
             * */
            if (getUrl().getParameter(Constants.CHANNEL_SEND_READONLYEVENT_KEY, true)) {
                // 向所有 client 发送 read only 事件，client 在收到 read only 事件之后，channel 就会变为只读，不能在发送数据了
                sendChannelReadOnlyEvent();
            }
            /**
             * 当 server 向所有客户端发送完 read only 事件之后，就会在这里等待 timeout (10s) 的时间，等待这个时间就是为了
             * 等注册中心通知 consumer,然后 consumer 销毁相应的 dubboInvoker。
             *
             * see : org.apache.dubbo.registry.integration.RegistryDirectory#destroyUnusedInvokers(java.util.Map, java.util.Map)
             *
             * 销毁 dubboInvoker 核心就是主动关闭客户端连接（由客户端主动关闭连接，避免 server 端过多的 time wait）
             *
             * 1. 说白了，server 这里等待 timeout (10s) 就是为了等客户端来主动关闭连接
             *
             * 2. 还有一个作用就是 server 可能正在处理之前的请求，那么在这里等待一段时间，等这些现有请求都处理完成，向客户端发送响应
             * 客户端虽然是 read only 了，但仍然可以接受来自 server 的响应
             *
             * 3. client 连接上如果还有未响应的 futrue , 也会等待一段时间，如果等待超时，则会自己创建一个状态码将连接关闭的 Response 交给 DefaultFuture 处理
             * 然后在关闭连接
             *
             * */
            while (HeaderExchangeServer.this.isRunning() // server 端的所有连接均已关闭时（客户端主动断连）running = false
                    && System.currentTimeMillis() - start < max) {
                try {
                    // 1. 等待客户端主动断开连接
                    // 2. 处理已有的请求，向客户端发送响应
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        doClose();
        // 超时强制关闭 ： org.apache.dubbo.remoting.transport.netty4.NettyServer.doClose
        server.close(timeout);
    }

    @Override
    public void startClose() {
        server.startClose();
    }

    private void sendChannelReadOnlyEvent() {
        Request request = new Request();
        request.setEvent(READONLY_EVENT);
        request.setTwoWay(false);
        request.setVersion(Version.getProtocolVersion());

        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {
            try {
                if (channel.isConnected()) {
                    channel.send(request, getUrl().getParameter(Constants.CHANNEL_READONLYEVENT_SENT_KEY, true));
                }
            } catch (RemotingException e) {
                logger.warn("send cannot write message error.", e);
            }
        }
    }

    private void doClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelCloseTask();
    }

    private void cancelCloseTask() {
        if (closeTimerTask != null) {
            closeTimerTask.cancel();
        }
    }

    @Override
    public Collection<ExchangeChannel> getExchangeChannels() {
        Collection<ExchangeChannel> exchangeChannels = new ArrayList<ExchangeChannel>();
        Collection<Channel> channels = server.getChannels();
        if (CollectionUtils.isNotEmpty(channels)) {
            for (Channel channel : channels) {
                exchangeChannels.add(HeaderExchangeChannel.getOrAddChannel(channel));
            }
        }
        return exchangeChannels;
    }

    @Override
    public ExchangeChannel getExchangeChannel(InetSocketAddress remoteAddress) {
        Channel channel = server.getChannel(remoteAddress);
        return HeaderExchangeChannel.getOrAddChannel(channel);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Collection<Channel> getChannels() {
        return (Collection) getExchangeChannels();
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return getExchangeChannel(remoteAddress);
    }

    @Override
    public boolean isBound() {
        return server.isBound();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return server.getLocalAddress();
    }

    @Override
    public URL getUrl() {
        return server.getUrl();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return server.getChannelHandler();
    }

    @Override
    public void reset(URL url) {
        server.reset(url);
        try {
            int currHeartbeat = getHeartbeat(getUrl());
            int currIdleTimeout = getIdleTimeout(getUrl());
            int heartbeat = getHeartbeat(url);
            int idleTimeout = getIdleTimeout(url);
            if (currHeartbeat != heartbeat || currIdleTimeout != idleTimeout) {
                cancelCloseTask();
                startIdleCheckTask(url);
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    @Override
    @Deprecated
    public void reset(org.apache.dubbo.common.Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    @Override
    public void send(Object message) throws RemotingException {
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message
                    + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        server.send(message);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message
                    + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        server.send(message, sent);
    }

    /**
     * Each interval cannot be less than 1000ms.
     */
    private long calculateLeastDuration(int time) {
        if (time / HEARTBEAT_CHECK_TICK <= 0) {
            return LEAST_HEARTBEAT_DURATION;
        } else {
            return time / HEARTBEAT_CHECK_TICK;
        }
    }

    private void startIdleCheckTask(URL url) {
        // netty server 返回 true
        if (!server.canHandleIdle()) {
            AbstractTimerTask.ChannelProvider cp = () -> unmodifiableCollection(HeaderExchangeServer.this.getChannels());
            // heartBeat * 3
            int idleTimeout = getIdleTimeout(url);
            // heartBeat
            long idleTimeoutTick = calculateLeastDuration(idleTimeout);
            // idleTimeoutTick 心跳发送间隔，连接空闲时间 idleTimeout
            // 每个 idleTimeoutTick 间隔，检查 channel 读或者写的空闲时间是否达到 idleTimeout
            // 已经达到则关闭连接
            CloseTimerTask closeTimerTask = new CloseTimerTask(cp, idleTimeoutTick, idleTimeout);
            this.closeTimerTask = closeTimerTask;

            // init task and start timer.
            IDLE_CHECK_TIMER.newTimeout(closeTimerTask, idleTimeoutTick, TimeUnit.MILLISECONDS);
        }
    }
}
