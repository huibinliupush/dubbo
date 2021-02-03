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

import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.spring.beans.factory.annotation.ReferenceAnnotationBeanPostProcessor;
import org.apache.dubbo.config.spring.beans.factory.annotation.ServiceAnnotationBeanPostProcessor;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.apache.dubbo.config.spring.util.DubboBeanUtils.registerCommonBeans;
import static org.springframework.beans.factory.support.BeanDefinitionBuilder.rootBeanDefinition;

/**
 * Dubbo {@link DubboComponentScan} Bean Registrar
 * ImportBeanDefnitionRegistor类是用来在spring处理Configuration配置类的时候，注册额外的自定义的bean
 *
 * 当处理Java编程式配置类(使用了@Configuration的类)的时候
 * ImportBeanDefinitionRegistrar接口的实现类可以注册额外的bean definitions;
 * ImportBeanDefinitionRegistrar接口的实现类必须提供给@Import注解或者是ImportSelector接口返回值
 *
 * DubboComponentScanRegistrar类继承ImportBeanDefinitionRegistor类用来处理自定义的注册bean规则
 * @see Service
 * @see DubboComponentScan
 * @see ImportBeanDefinitionRegistrar
 * @see ServiceAnnotationBeanPostProcessor
 * @see ReferenceAnnotationBeanPostProcessor
 * @since 2.5.7
 */
public class DubboComponentScanRegistrar implements ImportBeanDefinitionRegistrar {

    /**
     * 该方法用来根据定义的注解原信息处理自定义bean的注册
     * 这里会根据@DubboComponentScan注解中得原信息basePackages（扫描自定义bean所在的包路径）
     * 扫描指定包路径下标注@service的类并将其暴露为dubbo服务 *这里主要是处理@service 不会处理@reference*
     * 处理spring bean中标注@reference的属性 使其引用dubbo服务 注意 该类必须为spring bean.
     * dubbo并不会将@reference标注的类 提升为spring bean
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {

        //这个路径只是去处理@service
        Set<String> packagesToScan = getPackagesToScan(importingClassMetadata);
        // 注册ServiceAnnotationBeanPostProcessor类 用来处理 serviceBean的 注册
        registerServiceAnnotationBeanPostProcessor(packagesToScan, registry);

        // @since 2.7.6 Register the common beans
        //注册ReferenceAnnotationBeanPostProcessor类 用来处理 referenceBean的注册
        //在所有注册的bean中 去查找标注@reference的类 创建服务引用 *和扫描路径 packageToScan没关系*
        registerCommonBeans(registry);
    }

    /**
     * Registers {@link ServiceAnnotationBeanPostProcessor}
     *
     * @param packagesToScan packages to scan without resolving placeholders
     * @param registry       {@link BeanDefinitionRegistry}
     * @since 2.5.8
     */
    private void registerServiceAnnotationBeanPostProcessor(Set<String> packagesToScan, BeanDefinitionRegistry registry) {

        BeanDefinitionBuilder builder = rootBeanDefinition(ServiceAnnotationBeanPostProcessor.class);
        builder.addConstructorArgValue(packagesToScan);
        builder.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        BeanDefinitionReaderUtils.registerWithGeneratedName(beanDefinition, registry);

    }

    private Set<String> getPackagesToScan(AnnotationMetadata metadata) {
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                metadata.getAnnotationAttributes(DubboComponentScan.class.getName()));
        String[] basePackages = attributes.getStringArray("basePackages");
        Class<?>[] basePackageClasses = attributes.getClassArray("basePackageClasses");
        String[] value = attributes.getStringArray("value");
        // Appends value array attributes
        Set<String> packagesToScan = new LinkedHashSet<String>(Arrays.asList(value));
        packagesToScan.addAll(Arrays.asList(basePackages));
        for (Class<?> basePackageClass : basePackageClasses) {
            packagesToScan.add(ClassUtils.getPackageName(basePackageClass));
        }
        if (packagesToScan.isEmpty()) {
            return Collections.singleton(ClassUtils.getPackageName(metadata.getClassName()));
        }
        return packagesToScan;
    }

}
