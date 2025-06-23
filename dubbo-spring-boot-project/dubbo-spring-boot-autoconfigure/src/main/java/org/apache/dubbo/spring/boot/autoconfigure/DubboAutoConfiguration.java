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

import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.config.spring.beans.factory.annotation.ReferenceAnnotationBeanPostProcessor;
import org.apache.dubbo.config.spring.beans.factory.annotation.ServiceAnnotationPostProcessor;
import org.apache.dubbo.config.spring.context.annotation.EnableDubboConfig;
import org.apache.dubbo.config.spring.util.SpringCompatUtils;

import java.util.Collection;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.apache.dubbo.spring.boot.util.DubboUtils.BASE_PACKAGES_BEAN_NAME;
import static org.apache.dubbo.spring.boot.util.DubboUtils.BASE_PACKAGES_PROPERTY_NAME;
import static org.apache.dubbo.spring.boot.util.DubboUtils.DUBBO_PREFIX;
import static org.apache.dubbo.spring.boot.util.DubboUtils.DUBBO_SCAN_PREFIX;

/**
 * Dubbo Auto {@link Configuration}
 *
 * @see DubboReference
 * @see DubboService
 * @see ServiceAnnotationPostProcessor
 * @see ReferenceAnnotationBeanPostProcessor
 * @since 2.7.0
 */
// 根据配置文件（如 application.properties 或 application.yml,环境变量, 系统属性等）
// 中特定属性的值或存在性来决定一个 Bean 是否应该被创建，或者一个配置类是否应该生效。
// 实现配置驱动的 Bean 注册或配置类激活。它让应用程序的行为能够根据外部配置灵活变化
@ConditionalOnProperty(prefix = DUBBO_PREFIX, name = "enabled", matchIfMissing = true)
@Configuration
/**
 * 当 Spring Boot 应用启动时：
 *
 *  1. 它会从 META-INF/spring.factories 文件中加载所有声明的自动配置类
 *
 *  2. 这些配置类可能相互依赖（例如 A 需要 B 注册的 Bean）
 *
 *  3. @AutoConfigureAfter 明确告诉 Spring Boot："我的配置必须在这些配置类之后加载"
 *
 *  用于控制自动配置类（Auto-configuration classes）的加载顺序。它确保某个自动配置类在指定的其他自动配置类之后加载，
 *  这对于处理配置类之间的依赖关系至关重要
 *
 * 工作位置：
 *
 *  1. 只用于自动配置类（标注 @Configuration 且在 spring.factories 中声明）
 *
 *  2. 不能用于普通 @Configuration 类
 * */
@AutoConfigureAfter(DubboRelaxedBindingAutoConfiguration.class)
@EnableDubboConfig
public class DubboAutoConfiguration {

    /**
     * Creates {@link ServiceAnnotationPostProcessor} Bean
     *
     * @param packagesToScan the packages to scan
     * @return {@link ServiceAnnotationPostProcessor}
     */
    @ConditionalOnProperty(prefix = DUBBO_SCAN_PREFIX, name = BASE_PACKAGES_PROPERTY_NAME)
    @ConditionalOnBean(name = BASE_PACKAGES_BEAN_NAME)
    @Bean // 方法参数会移动依赖注入
    public static ServiceAnnotationPostProcessor serviceAnnotationBeanProcessor(
            @Qualifier(BASE_PACKAGES_BEAN_NAME) Set<String> packagesToScan) {
        // BASE_PACKAGES_BEAN_NAME 的注册
        // see : org.apache.dubbo.spring.boot.autoconfigure.DubboRelaxedBindingAutoConfiguration.dubboBasePackages
        ServiceAnnotationPostProcessor serviceAnnotationPostProcessor;
        try {
            serviceAnnotationPostProcessor =
                    (ServiceAnnotationPostProcessor) SpringCompatUtils.serviceAnnotationPostProcessor()
                            .getDeclaredConstructor(Collection.class)
                            .newInstance(packagesToScan);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return serviceAnnotationPostProcessor;
    }
}
