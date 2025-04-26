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
package org.apache.dubbo.demo.consumer;

import org.apache.dubbo.demo.DemoService;

import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.concurrent.CompletableFuture;

public class Application {
    /**
     * In order to make sure multicast registry works, need to specify '-Djava.net.preferIPv4Stack=true' before
     * launch the application
     *
     * BeanFactory 是用来产生 spring 中所有的 bean 的，当然包括产生 FactoryBean，相当于是所有 bean 的一个容器
     * FactoryBean 也是用来产生 bean 的，但这个 bean 是特定类型的 bean, 比如这里的 ReferenceBean，专门用来产生 proxy
     *
     * BeanFactory 产生 FactoryBean，FactoryBean 产生具体的 Bean
     *
     * 在 spring 中 getBean 的时候，首先会通过 BeanFactory 去 getBean，如果通过 beanId 获取到的是一个正常的 bean 那么就直接返回
     * 如果获取到的是一个 FactoryBean（beanId），那么就需要调用 FactoryBean 的 getObject 方法获取 bean
     */
    public static void main(String[] args) throws Exception {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/dubbo-consumer.xml");
        context.start();
        // 通过 referenceConfig 获取 proxy
        // org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean
        // org.springframework.beans.factory.support.FactoryBeanRegistrySupport.doGetObjectFromFactoryBean
        // org.apache.dubbo.config.spring.ReferenceBean.getObject 在该方法中获取 demoService

        // ReferenceBean 继承 referenceConfig，而 ReferenceBean 是一个 FactoryBean
        // <dubbo:reference id="demoService"> 在解析 xml 的时候本身会将 ReferenceBean 注册成一个 bean ，beanId = demoService
        // 但这里需要注意的是 beanId = demoService 此时在 spring 中对应的 bean 实例为 ReferenceBean，它是一个 FactoryBean
        // 当我们调用 context.getBean 的时候，通过 beanId 找到的是 ReferenceBean，明显不是 DemoService.class
        // 此时 spring 会判断 ReferenceBean 是不是一个 FactoryBean，如果是，那么就调用它的 getObject 方法获取真正的 bean
        // 如果不是 FactoryBean，就常规返回。
        DemoService demoService = context.getBean("demoService", DemoService.class);

        System.out.println("result: " + demoService.sayHello("world"));

        Thread.sleep(500);

        System.out.println("result: " + demoService.sayHello("world"));

        Thread.sleep(500);

        System.out.println("result: " + demoService.sayHello("world"));
        System.in.read();
    }
}
