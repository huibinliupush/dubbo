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
package org.apache.dubbo.remoting.transport.netty4.ssl;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.ssl.AuthPolicy;
import org.apache.dubbo.common.ssl.Cert;
import org.apache.dubbo.common.ssl.CertManager;
import org.apache.dubbo.common.ssl.ProviderCert;

import javax.net.ssl.SSLException;

import java.io.IOException;
import java.io.InputStream;
import java.security.Provider;
import java.security.Security;

import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;

import static org.apache.dubbo.common.constants.LoggerCodeConstants.TRANSPORT_FAILED_CLOSE_STREAM;

public class SslContexts {

    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(SslContexts.class);

    public static SslContext buildServerSslContext(ProviderCert providerConnectionConfig) {
        SslContextBuilder sslClientContextBuilder;
        InputStream serverKeyCertChainPathStream = null;
        InputStream serverPrivateKeyPathStream = null;
        InputStream serverTrustCertStream = null;
        try {
            /**
             * 哪端需要验证证书，那么就需要在哪端配置 trustManager，哪端需要提供证书，那么就需要在哪端配置证书，私钥
             * */
            // 服务端证书（可用自定义 Ca 签发）
            serverKeyCertChainPathStream = providerConnectionConfig.getKeyCertChainInputStream();
            // 服务端私钥
            serverPrivateKeyPathStream = providerConnectionConfig.getPrivateKeyInputStream();
            // 颁发正式的 ca , 用于信任对端发送过来的证书
            // 只有在启动双向认证的时候，服务端才会配置 serverTrustCertStream，用它来验证客户端的证书是否合法
            // 如果只是单向认证，那么服务端就不需要配置了，只需要配置服务端的证书，私钥
            // 但客户端需要认证服务端的证书，所以客户端需要配置 trustManager
            serverTrustCertStream = providerConnectionConfig.getTrustCertInputStream();
            String password = providerConnectionConfig.getPassword();
            if (password != null) {
                sslClientContextBuilder =
                        SslContextBuilder.forServer(serverKeyCertChainPathStream, serverPrivateKeyPathStream, password);
            } else {
                sslClientContextBuilder =
                        SslContextBuilder.forServer(serverKeyCertChainPathStream, serverPrivateKeyPathStream);
            }

            if (serverTrustCertStream != null) {
                /**
                 * trustManager 决定了 SSL/TLS 连接中客户端或服务器如何验证对方的证书（TLS/SSL 连接中实现证书验证的核心组件）
                 * 1. 验证对端身份
                 *    - 当作为 **客户端** 时：`trustManager` 用于验证服务器的证书是否可信（例如是否由受信任的 CA 签发）。
                 *    - 当作为 **服务器** 时（启用双向认证）：`trustManager` 用于验证客户端的证书。
                 * 2. 信任存储（TrustStore）管理
                 *    `trustManager` 通常基于一个 **信任存储**（包含可信证书的仓库），该存储可以是：
                 *    - 默认的 Java 信任库（`cacerts`）
                 *    - 自定义的 `.jks` 或 `.pem` 文件
                 *    - 内存中的证书对象
                 *
                 * 场景 1：客户端验证服务器证书（最常见）
                 * - **客户端**：必须配置 `trustManager`（默认使用系统 CA 或自定义 CA）
                 * - **服务器**：不需要设置 `trustManager`（除非启用双向认证）
                 *
                 * 哪端需要验证证书，那么就需要在哪端配置 trustManager，哪端需要提供证书，那么就需要在哪端配置证书，私钥
                 * 单向认证： 客户端需要认证服务端的证书，所以客户端需要配置 trustManager，客户端不需要配置客户端证书，以及私钥
                 * 由于只需要认证服务端，所以服务端需要配置服务端证书和私钥，服务端不需要配置 trustManager
                 *
                 *
                 * 双向认证：客户端和服务端都需要认证对方的证书，所以两端都必须配置 trustManager，同时两端也必须配置各自的证书和私钥
                 *
                 * 服务端配置（要求验证客户端）
                 * SslContext serverCtx = SslContextBuilder.forServer(serverCert, serverKey)
                 *     .trustManager(trustedCAs) // 指定信任哪些客户端证书的签发者
                 *     .clientAuth(ClientAuth.REQUIRE) // 强制要求客户端证书
                 *     .build();
                 *
                 * https://chat.deepseek.com/a/chat/s/a70c8208-fb99-4fe8-98b2-be171eae84b3
                 * 具体示例可查看 sample-ssl
                 *
                 * // 加载自定义 CA 证书（PEM 格式）
                 * File caCert = new File("path/to/custom-ca.pem");
                 * SslContext sslCtx = SslContextBuilder.forClient()
                 *     .trustManager(caCert)
                 *     .build();
                 *
                 * 双向验证
                 *
                 * // 服务端配置
                 * SslContext serverCtx = SslContextBuilder.forServer(serverCert, serverKey)
                 *     .trustManager(clientCAs) // 指定信任的客户端CA
                 *     .clientAuth(ClientAuth.REQUIRE) // 强制要求客户端证书
                 *     .build();
                 *
                 * // 客户端配置
                 * SslContext clientCtx = SslContextBuilder.forClient()
                 *     .keyManager(clientCert, clientKey) // 提供客户端证书
                 *     .trustManager(serverCA) // 信任服务器CA
                 *     .build();
                 *
                 * 自定义CA通常用于内部网络或开发测试环境，允许你创建自己的根证书，并用它来签发服务器或客户端证书。这样可以避免购买公共CA证书，同时确保内部系统的安全通信
                 * */
                sslClientContextBuilder.trustManager(serverTrustCertStream);
                if (providerConnectionConfig.getAuthPolicy() == AuthPolicy.CLIENT_AUTH) {
                    // 强制要求客户端证书
                    sslClientContextBuilder.clientAuth(ClientAuth.REQUIRE);
                } else {
                    sslClientContextBuilder.clientAuth(ClientAuth.OPTIONAL);
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not find certificate file or the certificate is invalid.", e);
        } finally {
            safeCloseStream(serverTrustCertStream);
            safeCloseStream(serverKeyCertChainPathStream);
            safeCloseStream(serverPrivateKeyPathStream);
        }
        try {
            return sslClientContextBuilder
                    .sslProvider(findSslProvider())
                    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE, // 如果选择失败，不发送任何协议
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,// 即使选择后失败也接受连接
                            ApplicationProtocolNames.HTTP_2,  // 支持的协议列表，按优先级排序
                            ApplicationProtocolNames.HTTP_1_1)) // 按服务器配置的协议列表顺序匹配
                    .build(); // 在大多数情况下，我们通过合理配置服务器和客户端的协议优先级顺序来满足需求，而不是自定义选择算法。
        } catch (SSLException e) {
            throw new IllegalStateException("Build SslSession failed.", e);
        }
    }

    public static SslContext buildClientSslContext(URL url) {
        CertManager certManager =
                url.getOrDefaultFrameworkModel().getBeanFactory().getBean(CertManager.class);
        Cert consumerConnectionConfig = certManager.getConsumerConnectionConfig(url);
        if (consumerConnectionConfig == null) {
            return null;
        }

        SslContextBuilder builder = SslContextBuilder.forClient();
        InputStream clientTrustCertCollectionPath = null;
        InputStream clientCertChainFilePath = null;
        InputStream clientPrivateKeyFilePath = null;
        try {
            clientTrustCertCollectionPath = consumerConnectionConfig.getTrustCertInputStream();
            if (clientTrustCertCollectionPath != null) {
                builder.trustManager(clientTrustCertCollectionPath);
            }

            clientCertChainFilePath = consumerConnectionConfig.getKeyCertChainInputStream();
            clientPrivateKeyFilePath = consumerConnectionConfig.getPrivateKeyInputStream();
            if (clientCertChainFilePath != null && clientPrivateKeyFilePath != null) {
                String password = consumerConnectionConfig.getPassword();
                if (password != null) {
                    builder.keyManager(clientCertChainFilePath, clientPrivateKeyFilePath, password);
                } else {
                    builder.keyManager(clientCertChainFilePath, clientPrivateKeyFilePath);
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not find certificate file or find invalid certificate.", e);
        } finally {
            safeCloseStream(clientTrustCertCollectionPath);
            safeCloseStream(clientCertChainFilePath);
            safeCloseStream(clientPrivateKeyFilePath);
        }
        try {
            return builder.sslProvider(findSslProvider()).build();
        } catch (SSLException e) {
            throw new IllegalStateException("Build SslSession failed.", e);
        }
    }

    /**
     * Returns OpenSSL if available, otherwise returns the JDK provider.
     */
    private static SslProvider findSslProvider() {
        if (OpenSsl.isAvailable()) {
            logger.debug("Using OPENSSL provider.");
            return SslProvider.OPENSSL;
        }
        if (checkJdkProvider()) {
            logger.debug("Using JDK provider.");
            return SslProvider.JDK;
        }
        throw new IllegalStateException(
                "Could not find any valid TLS provider, please check your dependency or deployment environment, "
                        + "usually netty-tcnative, Conscrypt, or Jetty NPN/ALPN is needed.");
    }

    private static boolean checkJdkProvider() {
        Provider[] jdkProviders = Security.getProviders("SSLContext.TLS");
        return (jdkProviders != null && jdkProviders.length > 0);
    }

    private static void safeCloseStream(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            logger.warn(TRANSPORT_FAILED_CLOSE_STREAM, "", "", "Failed to close a stream.", e);
        }
    }
}
