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
package org.apache.dubbo.spring.boot.autoconfigure;

import org.apache.dubbo.rpc.protocol.tri.ServletExchanger;
import org.apache.dubbo.rpc.protocol.tri.servlet.TripleFilter;
import org.apache.dubbo.rpc.protocol.tri.websocket.TripleWebSocketFilter;

import javax.servlet.Filter;

import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.http2.Http2Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.web.embedded.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
// proxyBeanMethods 决定了 Spring 是否应该通过 CGLIB 代理 来拦截 @Configuration 类中的 @Bean 方法调用，
// 其核心目的是：解决配置类内部 @Bean 方法相互调用时的单例行为问题
@Configuration(proxyBeanMethods = false)
/**
 * @Conditional 通过关联的 Condition 实现类(SpringBoot12Condition)在运行时进行条件判断：
 *
 *  1. 当条件满足时：注册 Bean 或加载配置类
 *
 *  2. 当条件不满足时：跳过注册或加载
 *
 *  Condition 接口参数：
 *
 *  1. ConditionContext 提供访问 Spring 容器的能力，包含 BeanFactory，Environment，ResourceLoader，ClassLoader
 *  2. AnnotatedTypeMetadata ， 获取 @Conditional 相关属性
 *
 *  Spring Boot 自动配置流程
 *      1. 加载 META-INF/spring.factories 中的自动配置类
 *
 *      2. 过滤排除项 (@EnableAutoConfiguration.exclude)
 *
 *      3. 按 @AutoConfigureOrder 排序
 *
 *      4. 应用条件注解过滤
 *
 *      5. 实例化并注册符合条件的配置类
 *
 * */
@Conditional(SpringBoot12Condition.class) // spring 1 or 2 生效
public class DubboTripleAutoConfiguration {

    public static final String SERVLET_PREFIX = "dubbo.protocol.triple.servlet";

    public static final String WEBSOCKET_PREFIX = "dubbo.protocol.triple.websocket";

    @Configuration(proxyBeanMethods = false)
    /**
     * 根据类路径中是否存在指定的类来决定配置是否生效
     * 参数详解：
     *   1. value	Class<?>[]	直接引用类对象（编译时检查）
     *   2. name	String[]	类全限定名字符串（运行时检查，更安全）
     *
     * */
    @ConditionalOnClass(Filter.class) // 当类路径中存在指定类时生效
    /**
     * @ConditionalOnWebApplication 是 Spring Boot 中用于检测当前应用是否为 Web 应用的核心条件注解。
     * 它确保相关配置只在 Web 环境下生效，是 Spring Boot 自动配置机制中区分 Web 和非 Web 环境的关键组件。
     *
     *  ANY	     默认值，匹配任何Web应用（Servlet或Reactive）
     *  SERVLET	 仅匹配基于Servlet的Web应用（如Spring MVC）
     *  REACTIVE 仅匹配响应式Web应用（如Spring WebFlux）
     *
     * */
    @ConditionalOnWebApplication(type = Type.SERVLET)
    @ConditionalOnProperty(prefix = SERVLET_PREFIX, name = "enabled", havingValue = "true")
    public static class TripleServletConfiguration {

        @Bean
        public FilterRegistrationBean<TripleFilter> tripleProtocolFilter(
                @Value("${" + SERVLET_PREFIX + ".filter-url-patterns:/*}") String[] urlPatterns,
                @Value("${" + SERVLET_PREFIX + ".filter-order:-1000000}") int order,
                @Value("${server.port:8080}") int serverPort) {
            ServletExchanger.bindServerPort(serverPort);
            FilterRegistrationBean<TripleFilter> registrationBean = new FilterRegistrationBean<>();
            registrationBean.setFilter(new TripleFilter());
            registrationBean.addUrlPatterns(urlPatterns);
            registrationBean.setOrder(order);
            return registrationBean;
        }

        @Bean
        @ConditionalOnClass(Http2Protocol.class)
        @ConditionalOnProperty(prefix = SERVLET_PREFIX, name = "max-concurrent-streams")
        public WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> tripleTomcatHttp2Customizer(
                @Value("${" + SERVLET_PREFIX + ".max-concurrent-streams}") int maxConcurrentStreams) {
            return factory -> factory.addConnectorCustomizers(connector -> {
                ProtocolHandler handler = connector.getProtocolHandler();
                for (UpgradeProtocol upgradeProtocol : handler.findUpgradeProtocols()) {
                    if (upgradeProtocol instanceof Http2Protocol) {
                        Http2Protocol protocol = (Http2Protocol) upgradeProtocol;
                        int value = maxConcurrentStreams <= 0 ? Integer.MAX_VALUE : maxConcurrentStreams;
                        protocol.setMaxConcurrentStreams(value);
                        protocol.setMaxConcurrentStreamExecution(value);
                    }
                }
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Filter.class)
    @ConditionalOnWebApplication(type = Type.SERVLET)
    @ConditionalOnProperty(prefix = WEBSOCKET_PREFIX, name = "enabled", havingValue = "true")
    public static class TripleWebSocketConfiguration {

        @Bean
        public FilterRegistrationBean<TripleWebSocketFilter> tripleWebSocketFilter(
                @Value("${" + WEBSOCKET_PREFIX + ".filter-url-patterns:/*}") String[] urlPatterns,
                @Value("${" + WEBSOCKET_PREFIX + ".filter-order:-1000000}") int order,
                @Value("${server.port:8080}") int serverPort) {
            ServletExchanger.bindServerPort(serverPort);
            FilterRegistrationBean<TripleWebSocketFilter> registrationBean = new FilterRegistrationBean<>();
            registrationBean.setFilter(new TripleWebSocketFilter());
            registrationBean.addUrlPatterns(urlPatterns);
            registrationBean.setOrder(order);
            return registrationBean;
        }
    }
}
