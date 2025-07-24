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
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.ssl.CertManager;
import org.apache.dubbo.common.ssl.ProviderCert;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.api.ProtocolDetector;
import org.apache.dubbo.remoting.api.WireProtocol;
import org.apache.dubbo.remoting.api.pu.AbstractPortUnificationServer;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.transport.netty4.ssl.SslContexts;

import javax.net.ssl.SSLSession;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.AttributeKey;

import static org.apache.dubbo.common.constants.LoggerCodeConstants.INTERNAL_ERROR;

public class NettyPortUnificationServerHandler extends ByteToMessageDecoder {

    private static final ErrorTypeAwareLogger LOGGER =
            LoggerFactory.getErrorTypeAwareLogger(NettyPortUnificationServerHandler.class);
    private final URL url;
    // NettyPortUnificationServer -> MultiMessageHandler -> HeartbeatHandler -> AllChannelHandler -> DefaultPuHandler(空实现)
    private final ChannelHandler handler;
    private final boolean detectSsl;
    // 在 doOpen 的时候填充
    // see ; org.apache.dubbo.remoting.api.pu.AbstractPortUnificationServer.doOpen
    private final Map<String, WireProtocol> protocols;
    // 在 server open 之后进行填充
    // see : org.apache.dubbo.remoting.exchange.PortUnificationExchanger.bind
    // AbstractPortUnificationServer.supportedUrls
    private final Map<String, URL> urlMapper;
    // org.apache.dubbo.remoting.api.pu.AbstractPortUnificationServer.supportedHandlers
    private final Map<String, ChannelHandler> handlerMapper;
    private static final AttributeKey<SSLSession> SSL_SESSION_KEY = AttributeKey.valueOf(Constants.SSL_SESSION_KEY);

    public NettyPortUnificationServerHandler(
            URL url,
            boolean detectSsl,
            Map<String, WireProtocol> protocols,
            ChannelHandler handler,
            Map<String, URL> urlMapper,
            Map<String, ChannelHandler> handlerMapper) {
        this.url = url;
        this.protocols = protocols;
        this.detectSsl = detectSsl;
        this.handler = handler;
        // AbstractPortUnificationServer.supportedUrls
        this.urlMapper = urlMapper;
        // org.apache.dubbo.remoting.api.pu.AbstractPortUnificationServer.supportedHandlers
        this.handlerMapper = handlerMapper;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error(
                INTERNAL_ERROR,
                "unknown error in remoting module",
                "",
                "Unexpected exception from downstream before protocol detected.",
                cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // see : org.apache.dubbo.remoting.transport.netty4.NettyPortUnificationServerHandler.enableSsl
        if (evt instanceof SslHandshakeCompletionEvent) {
            SslHandshakeCompletionEvent handshakeEvent = (SslHandshakeCompletionEvent) evt;
            if (handshakeEvent.isSuccess()) {
                SSLSession session =
                        ctx.pipeline().get(SslHandler.class).engine().getSession();
                LOGGER.info("TLS negotiation succeed with session: " + session);
                ctx.channel().attr(SSL_SESSION_KEY).set(session);
            } else {
                LOGGER.error(
                        INTERNAL_ERROR,
                        "",
                        "",
                        "TLS negotiation failed when trying to accept new connection.",
                        handshakeEvent.cause());
                ctx.close();
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        // Will use the first five bytes to detect a protocol.
        // size of telnet command ls is 2 bytes
        if (in.readableBytes() < 2) {
            return;
        }

        CertManager certManager =
                url.getOrDefaultFrameworkModel().getBeanFactory().getBean(CertManager.class);
        ProviderCert providerConnectionConfig =
                certManager.getProviderConnectionConfig(url, ctx.channel().remoteAddress());

        if (providerConnectionConfig != null && isSsl(in)) {
            // tls 上的协议协商，这样来探测 ssl 有点鸡肋，ssl 不应该探测，而是强制写死 pipeline
            // 因为既然应用配置了 ssl , 肯定是希望全部是 ssl 请求，非 ssl 请求不会接收
            // 如果 ssl 都要进行探测，那么应用虽然配置了 ssl , 但客户端还是可以发起普通非安全的请求
            enableSsl(ctx, providerConnectionConfig);
        } else {
            // tcp 上的协议协商
            detectProtocol(ctx, url, channel, in);
        }
    }

    private void enableSsl(ChannelHandlerContext ctx, ProviderCert providerConnectionConfig) {
        ChannelPipeline p = ctx.pipeline();
        SslContext sslContext = SslContexts.buildServerSslContext(providerConnectionConfig);
        // ssl 会一直停留在 pipeline 中，握手成功之后，需要对称加解密
        p.addLast("ssl", sslContext.newHandler(ctx.alloc()));
        // 用于处理 SslHandshakeCompletionEvent
        // 配置所有协议的 pipeline，但需要 channelRead 根据协议头进行识别
        p.addLast(
                "unificationA",
                new NettyPortUnificationServerHandler(url, false, protocols, handler, urlMapper, handlerMapper));
        // configurePipeline 之后，会删除掉该 handler
        // 匿名内部类（适用于临时的一次性的，因为实现简单方便，不用专门为此定义一个类，临时用一下直接匿名内部类）
        p.addLast("ALPN", new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) { // 匿名内部类
            /**
             * 当客户端与服务器建立 TLS 连接时，双方需要明确后续通信使用的应用层协议（例如：HTTP/1.1 vs HTTP/2）。
             * 传统方式需额外通信（如 HTTP Upgrade）来协商协议，而 ALPN/NPN 将协商过程嵌入 TLS 握手，减少额外开销。
             *
             * ALPN (Application-Layer Protocol Negotiation)
             * 现代标准（RFC 7301），客户端在 TLS ClientHello 中发送支持的协议列表（如 ["h2", "http/1.1"]），
             * 服务器在 ServerHello 中返回选定的协议（如 "h2"）。
             *
             * Netty 的 SslHandler 在 TLS 握手完成后，即可获取协议协商结果
             * String protocol = sslEngine.getApplicationProtocol();
             *
             * 在 TLS 握手阶段即可确定后续应用层协议，避免额外通信开销
             *
             * 需要再 SslContext 中配置 ApplicationProtocolConfig 并指定 ALPN
             * org.apache.dubbo.remoting.transport.netty4.ssl.SslContexts#buildServerSslContext(org.apache.dubbo.common.ssl.ProviderCert)
             *
             *
             * 注意：要使 ALPN 生效，需满足：
             *
             *  1. JDK 9+ 或使用 Netty-tcnative (OpenSSL)
             *
             *  2. 客户端/服务器双方均支持 ALPN 扩展 (可以支持任意协议的协商，包括 websocket 和 dubbo)
             *  不过需要再客户端与服务器各自的 ApplicationProtocolConfig 中进行配置支持的协议
             *  握手成功之后我们就可以通过 sslEngine.getApplicationProtocol() 获取了
             *  然后在 configurePipeline 中直接配置对应协议的 pipeline
             *
             *  3. SSL 上下文正确配置协议列表
             *
             *
             * ALPN设计目标是支持任意应用层协议的协商，不仅限于HTTP/2。
             * 其工作原理是在TLS握手阶段（ClientHello/ServerHello）通过扩展字段交换协议标识符列表，由双方选择共同支持的协议
             *
             * h2c : tcp 上明文升级到 http2 ,http1 使用头字段 Connection:Upgrade 升级到 http2 , 服务端返回状态码 101 切换协议
             * see : org.apache.dubbo.rpc.protocol.tri.TripleHttp2Protocol#configurerHttp1Handlers(org.apache.dubbo.common.URL, java.util.List)
             *
             * */
            @Override
            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
                // ssl 握手成功之后回调，重新配置 pipeline
                // configurePipeline 方法执行完之后，该 handler 就会从 pipeline 中删除
                if (!ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                    return;
                }
                // dubbo 的逻辑 ：只负责配置 http2 pipeline, 不需要 channelRead, h2 在 ssl 握手成功之后即可识别（http1 也可以的）
                // 剩下的协议需要在 channelRead （NettyPortUnificationServerHandler）中根据协议头进行识别
                NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
                ByteBuf in = ctx.alloc().buffer();
                // in 是空的， detect 你妹啊
                // ApplicationProtocolNegotiationHandler 加在这里真他妈鸡肋，搞毛线
                // 其实最终还是要靠 NettyPortUnificationServerHandler 类来进行协议嗅探，配置 pipeline

                // 在匿名内部类中调用一个方法时，查找顺序是：先在当前匿名内部类中查找，然后在父类中查找，
                // 最后在外部类（本类）中查找（如果内部类是非静态的，则它可以访问外部类的成员）。
                // 因此，这里调用的detectProtocol()方法应该是外部类中的detectProtocol方法。
                // 内部类可以直接访问外部类(本类)的所有成员（包括私有方法），因为它们在同一个封闭作用域中
                detectProtocol(ctx, url, channel, in);
            }
        });
        // io.netty.handler.codec.ByteToMessageDecoder.handlerRemoved
        // 在 handlerRemoved 方法中会将本次 bytebuffer 继续向后 fireChannelRead
        // 不用担心 bytebuffer 会因为 ByteToMessageDecoder 的删除而泄露
        // 因为 ByteToMessageDecoder 对应的 ChannelHandlerContext 虽然从 pipeline 中删除了
        // 但是其 ChannelHandlerContext 的 next , prev 指针还是不变的，并没有清空
        // ChannelHandlerContext fireChannelRead 之后，原来的 bytebuffer 继续会被这里重新配置的 sslHandler 接收
        p.remove(this);
    }

    private boolean isSsl(ByteBuf buf) {
        // at least 5 bytes to determine if data is encrypted
        if (detectSsl && buf.readableBytes() >= 5) {
            return SslHandler.isEncrypted(buf);
        }
        return false;
    }

    private void detectProtocol(ChannelHandlerContext ctx, URL url, NettyChannel channel, ByteBuf in) {
        // server 端支持的协议，在 server doOpen 的时候进行填充
        // see : see ; org.apache.dubbo.remoting.api.pu.AbstractPortUnificationServer.doOpen
        Set<String> supportedProtocolNames = new HashSet<>(protocols.keySet());
        // 只保留已经暴露的协议，比如支持三个协议，但是实际只暴露了两个
        // 那么就只探测请求报文是否为这两个协议，其他的不用管，因为没有暴露
        supportedProtocolNames.retainAll(urlMapper.keySet());

        for (final String name : supportedProtocolNames) {
            // 获取对应协议的 WireProtocol，用于探测具体的协议
            // dubbo 协议对应 DubboWireProtocol
            // tri 协议对应 TripleHttp2Protocol
            // grpc 协议对应 GrpcHttp2Protocol
            WireProtocol protocol = protocols.get(name);
            in.markReaderIndex();
            ChannelBuffer buf = new NettyBackedChannelBuffer(in);
            // dubbo 协议对应 DubboDetector
            // tri and grpc 协议对应 TripleProtocolDetector
            // http2 对应 Http2ProtocolDetector
            // 根据协议头进行探测，每种协议的协议头都有特定长度的特定字节
            final ProtocolDetector.Result result = protocol.detector().detect(buf);
            in.resetReaderIndex();
            switch (result.flag()) {
                case UNRECOGNIZED:
                    continue;
                case RECOGNIZED:
                    // handler 这里是一连串的 dubbo channel handler , 后续会由 netty channel handler 进行驱动
                    // 不同协议对应不同的处理 handler,通常最后一个 dubbo channel handler 负责处理协议请求
                    // triple : MultiMessageHandler -> HeartbeatHandler -> AllChannelHandler -> DefaultPuHandler(空实现)
                    ChannelHandler localHandler = this.handlerMapper.getOrDefault(name, handler);
                    // 协议对应的 server 端 url, 以最后一个暴露的 service url 为准，里面包含了 server 端的相关配置
                    // 其实每个暴露的 service url 里面包含的 server 配置都是一样的，随便选一个就可以
                    URL localURL = this.urlMapper.getOrDefault(name, url);
                    channel.setUrl(localURL);
                    NettyConfigOperator operator = new NettyConfigOperator(channel, localHandler);
                    operator.setDetectResult(result);
                    // channel 中配置对应协议的 pipeline
                    protocol.configServerProtocolHandler(url, operator);
                    ctx.pipeline().remove(this);
                case NEED_MORE_DATA:
                    return;
                default:
                    return;
            }
        }
        // UNRECOGNIZED 分支中的 continue 会跳到这里（supportedProtocol 全部不能识别）
        byte[] preface = new byte[in.readableBytes()];
        in.readBytes(preface);
        Set<String> supported = url.getApplicationModel().getSupportedExtensions(WireProtocol.class);
        LOGGER.error(
                INTERNAL_ERROR,
                "unknown error in remoting module",
                "",
                String.format(
                        "Can not recognize protocol from downstream=%s . " + "preface=%s protocols=%s",
                        ctx.channel().remoteAddress(), Bytes.bytes2hex(preface), supported));

        // Unknown protocol; discard everything and close the connection.
        in.clear();
        ctx.close();
    }
}
