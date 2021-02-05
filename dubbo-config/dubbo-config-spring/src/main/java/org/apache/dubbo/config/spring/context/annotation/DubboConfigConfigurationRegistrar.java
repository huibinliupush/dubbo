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
package org.apache.dubbo.config.spring.context.annotation;

import org.apache.dubbo.config.AbstractConfig;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

import static com.alibaba.spring.util.AnnotatedBeanDefinitionRegistryUtils.registerBeans;
import static org.apache.dubbo.config.spring.util.DubboBeanUtils.registerCommonBeans;

/**
 * Dubbo {@link AbstractConfig Config} {@link ImportBeanDefinitionRegistrar register}, which order can be configured
 *

 *
 * Spring IoC容器允许BeanFactoryPostProcessor在容器实际实例化任何其它的bean之前读取配置元数据，并有可能修改它。
 * 如果你愿意，你可以配置多个BeanFactoryPostProcessor。
 * 你还能通过设置'order'属性来控制BeanFactoryPostProcessor的执行次序
 *
 * com.alibaba.spring.beans.factory.annotation.ConfigurationBeanBindingRegistrar#registerConfigurationBindingBeanPostProcessor(org.springframework.beans.factory.support.BeanDefinitionRegistry)
 * 用BeanPostProcessor来绑定 配置类的属性
 *
 * 实现BeanPostProcessor接口可以在Bean(实例化之后)初始化的前后做一些自定义的操作，但是拿到的参数只有BeanDefinition实例和BeanDefinition的名称，也就是无法修改BeanDefinition元数据
 *
 * BeanFactoryPostProcessor回调会先于BeanPostProcessor
 *
 *
 * @see EnableDubboConfig
 * @see DubboConfigConfiguration
 * @see Ordered
 * @since 2.5.8
 */
public class DubboConfigConfigurationRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        //importingClassMetadata为被注解@EnableDubboConfig标注的类的注解原信息
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableDubboConfig.class.getName()));

        boolean multiple = attributes.getBoolean("multiple");

        /**
         * 为了实现外部化配置中得前缀绑定dubbo配置类
         * 被@EnableConfigurationBeanBindings标注的dubbo配置bean DubboConfigConfiguration需要注册到spring上下文中
         * 需要@PropertySource指定外部化配置文件（一般在dubbo应用启动类中加）
         * */
        // 注册被 @EnableConfigurationBeanBindings标注的类 触发Single Config Bindings
        registerBeans(registry, DubboConfigConfiguration.Single.class);

        if (multiple) { // Since 2.6.6 https://github.com/apache/dubbo/issues/3193
            //触发 复数config类 绑定
            registerBeans(registry, DubboConfigConfiguration.Multiple.class);
        }

        // Since 2.7.6
        registerCommonBeans(registry);
    }
}
