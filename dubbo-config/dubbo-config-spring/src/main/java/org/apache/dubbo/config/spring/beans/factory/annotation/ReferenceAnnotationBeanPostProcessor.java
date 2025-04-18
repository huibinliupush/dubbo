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
package org.apache.dubbo.config.spring.beans.factory.annotation;

import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.ServiceBean;
import org.apache.dubbo.config.spring.context.event.ServiceBeanExportedEvent;

import com.alibaba.spring.beans.factory.annotation.AbstractAnnotationBeanPostProcessor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.InjectionMetadata;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.AnnotationAttributes;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.alibaba.spring.util.AnnotationUtils.getAttribute;
import static com.alibaba.spring.util.AnnotationUtils.getAttributes;
import static java.lang.reflect.Proxy.newProxyInstance;
import static org.apache.dubbo.config.spring.beans.factory.annotation.ServiceBeanNameBuilder.create;
import static org.springframework.util.StringUtils.hasText;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that Consumer service {@link Reference} annotated fields
 *
 * @see DubboReference
 * @see Reference
 * @see com.alibaba.dubbo.config.annotation.Reference
 * @since 2.5.7
 */
public class ReferenceAnnotationBeanPostProcessor extends AbstractAnnotationBeanPostProcessor implements
        ApplicationContextAware, ApplicationListener<ServiceBeanExportedEvent> {

    /**
     * The bean name of {@link ReferenceAnnotationBeanPostProcessor}
     */
    public static final String BEAN_NAME = "referenceAnnotationBeanPostProcessor";

    /**
     * Cache size
     */
    private static final int CACHE_SIZE = Integer.getInteger(BEAN_NAME + ".cache.size", 32);

    //缓存referenceBean  key为referenceBeanNmae
    private final ConcurrentMap<String, ReferenceBean<?>> referenceBeanCache =
            new ConcurrentHashMap<>(CACHE_SIZE);

    //缓存需要依赖注入的field和需要注入的referenceBean之间的映射
    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedFieldReferenceBeanCache =
            new ConcurrentHashMap<>(CACHE_SIZE);
    //缓存需要依赖注入的method和需要注入的referenceBean之间的映射
    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedMethodReferenceBeanCache =
            new ConcurrentHashMap<>(CACHE_SIZE);

    //缓存referencedBeanName（本地服务暴露的beanName） 和 用于创建本地serviceBean代理的 invocationHandler之间的映射
    private final ConcurrentMap<String, ReferencedBeanInvocationHandler> referencedBeanInvocationHandlersCache =
            new ConcurrentHashMap<>();

    private ApplicationContext applicationContext;

    /**
     * {@link com.alibaba.dubbo.config.annotation.Reference @com.alibaba.dubbo.config.annotation.Reference} has been supported since 2.7.3
     * <p>
     * {@link DubboReference @DubboReference} has been supported since 2.7.7
     */
    public ReferenceAnnotationBeanPostProcessor() {
        super(DubboReference.class, Reference.class, com.alibaba.dubbo.config.annotation.Reference.class);
    }

    /**
     * Gets all beans of {@link ReferenceBean}
     *
     * @return non-null read-only {@link Collection}
     * @since 2.5.9
     */
    public Collection<ReferenceBean<?>> getReferenceBeans() {
        return referenceBeanCache.values();
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected field.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedFieldReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedFieldReferenceBeanCache);
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected method.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedMethodReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedMethodReferenceBeanCache);
    }

    // bean 为依赖 dubbo 服务的 spring bean
    // injectedType 为 bean 中的字段或者方法需要依赖注入的类型（dubbo 接口）
    // attributes 为 bean 中的字段或者方法标注的 @DubboReference 注解
    // injectedElement 为 bean 中需要注入的地方（字段或者方法）
    // 该方法负责生成 dubbo reference 代理（也会提升为一个bean），然后注入到相应字段或者方法中（injectedElement）
    @Override
    protected Object doGetInjectedBean(AnnotationAttributes attributes, Object bean, String beanName, Class<?> injectedType,
                                       InjectionMetadata.InjectedElement injectedElement) throws Exception {
        /**
         * The name of bean that annotated Dubbo's {@link Service @Service} in local Spring {@link ApplicationContext}
         */
        //根据injectedType就是需要注入的DubboService,构造出serviceBeanName，后续会用这个serviceBeanName到spring中去查找
        //是否存在serviceBean来判断这个DubboService是不是本地暴露的服务，如果是本地暴露的服务并且协议不是inJvm就直接调用。
        String referencedBeanName = buildReferencedBeanName(attributes, injectedType);
        /**
         * The name of bean that is declared by {@link Reference @Reference} annotation injection
         * 注意这里即使相同的 interfaceName，如果 @DubboReference 中指定的属性不同，那么也会对应不同的代理示例
         */
        // 如果引用地不是本地暴露的服务，那么这个就是referenceBean，远程dubboService的代理引用。
        // 构造referenceBeanName : @Reference(注解属性key=value,key=value) interfaceName
        // @Reference 标注的属性不同，那么对应的 referenceBean 也不同，最好还是指明 id , 这样就会引用同一个 referenceBean
        String referenceBeanName = getReferenceBeanName(attributes, injectedType);

        //根据dubbo引用注解@reference的信息，将注解中配置的信息 构造referenceBean(这里就是dubbo注解驱动外部化配置的地方)
        ReferenceBean referenceBean = buildReferenceBeanIfAbsent(referenceBeanName, attributes, injectedType);

        //判断引用的dubbo服务是否为本地暴露的服务，如果是本地服务后边直接 反射调用服务实例的具体方法
        // 1. serviceBean 在当前springContext中
        // 2. referenceBean.isInjvm() = true(默认)
        boolean localServiceBean = isLocalServiceBean(referencedBeanName, referenceBean, attributes);

        // localServiceBean = true ，那么就直接调用本地 dubboservice, 就不会注册 ReferenceBean
        // 而是将 ReferenceBeanName 作为 dubboservice-> ref(demoServerImpl) 的别名
        // 通过 ReferenceBeanName 在 spring 中获取到的 bean 就是 demoServerImpl

        // localServiceBean = false，向 spring 注册 ReferenceBean
        registerReferenceBean(referencedBeanName, referenceBean, attributes, localServiceBean, injectedType);

        //缓存依赖注入的referenceBean 和 依赖注入的点（field,method）之间的关联
        cacheInjectedReferenceBean(referenceBean, injectedElement);

        //创建返回 远程服务引用代理（这里有本地服务和远程服务的区别）
        return getOrCreateProxy(referencedBeanName, referenceBean, localServiceBean, injectedType);
    }

    /**
     * Register an instance of {@link ReferenceBean} as a Spring Bean
     *
     * @param referencedBeanName The name of bean that annotated Dubbo's {@link Service @Service} in the Spring {@link ApplicationContext}
     * @param referenceBean      the instance of {@link ReferenceBean} is about to register into the Spring {@link ApplicationContext}
     * @param attributes         the {@link AnnotationAttributes attributes} of {@link Reference @Reference}
     * @param localServiceBean   Is Local Service bean or not
     * @param interfaceClass     the {@link Class class} of Service interface
     * @since 2.7.3
     */
    private void registerReferenceBean(String referencedBeanName, ReferenceBean referenceBean,
                                       AnnotationAttributes attributes,
                                       boolean localServiceBean, Class<?> interfaceClass) {

        ConfigurableListableBeanFactory beanFactory = getBeanFactory();

        // referenceBeanName
        String beanName = getReferenceBeanName(attributes, interfaceClass);

        if (localServiceBean) {  // If @Service bean is local one
            /**
             * Get  the @Service's BeanDefinition from {@link BeanFactory}
             * Refer to {@link ServiceAnnotationBeanPostProcessor#buildServiceBeanDefinition}
             */
            AbstractBeanDefinition beanDefinition = (AbstractBeanDefinition) beanFactory.getBeanDefinition(referencedBeanName);
            //获取本地服务引用的服务实例
            RuntimeBeanReference runtimeBeanReference = (RuntimeBeanReference) beanDefinition.getPropertyValues().get("ref");
            // The name of bean annotated @Service
            //获取本地服务引用的服务实例的BeanName
            String serviceBeanName = runtimeBeanReference.getBeanName();
            // register Alias rather than a new bean name, in order to reduce duplicated beans
            //在本地暴露的场景下，beanName是在服务引用下的概念 但其实他真正引用的就是本地服务实例serviceBeanName(服务暴露视角)
            //因为本地暴露，本地本身就存在servicebean（实际调用）但用户看到的其实是beanName(用户是站在服务引用视角)
            //所以将beanName设置为serviceBeanName的别名
            beanFactory.registerAlias(serviceBeanName, beanName);
        } else { // Remote @Service Bean
            //如果是远程引用，那就直接将这个referenceBean（里面包含了远程服务代理）注册到spring中
            if (!beanFactory.containsBean(beanName)) {
                // 这里要求 referenceBean 必须已经是初始化好的，spring 在这个方法中不会管初始化，因为BeanDefinition已经停止修改了
                // 不会调用 InitializingBean's afterPropertiesSet 方法，需要自己手动调用
                // see : org.apache.dubbo.config.spring.beans.factory.annotation.ReferenceBeanBuilder.postConfigureBean
                beanFactory.registerSingleton(beanName, referenceBean);
            }
        }
    }

    /**
     * Get the bean name of {@link ReferenceBean} if {@link Reference#id() id attribute} is present,
     * or {@link #generateReferenceBeanName(AnnotationAttributes, Class) generate}.
     *
     * @param attributes     the {@link AnnotationAttributes attributes} of {@link Reference @Reference}
     * @param interfaceClass the {@link Class class} of Service interface
     * @return non-null
     * @since 2.7.3
     */
    private String getReferenceBeanName(AnnotationAttributes attributes, Class<?> interfaceClass) {
        // id attribute appears since 2.7.3
        String beanName = getAttribute(attributes, "id");
        if (!hasText(beanName)) {
            beanName = generateReferenceBeanName(attributes, interfaceClass);
        }
        return beanName;
    }

    /**
     * Build the bean name of {@link ReferenceBean}
     *
     * @param attributes     the {@link AnnotationAttributes attributes} of {@link Reference @Reference}
     * @param interfaceClass the {@link Class class} of Service interface
     * @return
     * @since 2.7.3
     */
    private String generateReferenceBeanName(AnnotationAttributes attributes, Class<?> interfaceClass) {
        StringBuilder beanNameBuilder = new StringBuilder("@Reference");

        if (!attributes.isEmpty()) {
            beanNameBuilder.append('(');
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                beanNameBuilder.append(entry.getKey())
                        .append('=')
                        .append(entry.getValue())
                        .append(',');
            }
            // replace the latest "," to be ")"
            beanNameBuilder.setCharAt(beanNameBuilder.lastIndexOf(","), ')');
        }

        beanNameBuilder.append(" ").append(interfaceClass.getName());

        return beanNameBuilder.toString();
    }

    /**
     * Is Local Service bean or not?
     * 是否为本地暴露服务的依据是：1. serviceBean是否存在当前springContext中。2.暴露协议不能是inJvm
     * @param referencedBeanName the bean name to the referenced bean
     * @return If the target referenced bean is existed, return <code>true</code>, or <code>false</code>
     * @since 2.7.6
     */
    private boolean isLocalServiceBean(String referencedBeanName, ReferenceBean referenceBean, AnnotationAttributes attributes) {
        return existsServiceBean(referencedBeanName) && !isRemoteReferenceBean(referenceBean, attributes);
    }

    /**
     * Check the {@link ServiceBean} is exited or not
     *
     * @param referencedBeanName the bean name to the referenced bean
     * @return if exists, return <code>true</code>, or <code>false</code>
     * @revised 2.7.6
     */
    private boolean existsServiceBean(String referencedBeanName) {
        return applicationContext.containsBean(referencedBeanName) &&
                applicationContext.isTypeMatch(referencedBeanName, ServiceBean.class);

    }

    private boolean isRemoteReferenceBean(ReferenceBean referenceBean, AnnotationAttributes attributes) {
        boolean remote = Boolean.FALSE.equals(referenceBean.isInjvm()) || Boolean.FALSE.equals(attributes.get("injvm"));
        return remote;
    }

    /**
     * Get or Create a proxy of {@link ReferenceBean} for the specified the type of Dubbo service interface
     *
     * @param referencedBeanName   The name of bean that annotated Dubbo's {@link Service @Service} in the Spring {@link ApplicationContext}
     * @param referenceBean        the instance of {@link ReferenceBean}
     * @param localServiceBean     Is Local Service bean or not
     * @param serviceInterfaceType the type of Dubbo service interface
     * @return non-null
     * @since 2.7.4
     */
    private Object getOrCreateProxy(String referencedBeanName, ReferenceBean referenceBean, boolean localServiceBean,
                                    Class<?> serviceInterfaceType) {
        if (localServiceBean) { // If the local @Service Bean exists, build a proxy of Service
            // 调用本地暴露的服务时  直接反射调用service类的实现 serviceImpl(代理里直接调用实现类的方法 不走那些invoker链)
            // 该 Proxy 实现 serviceInterfaceType 接口，并将接口中的所有方法代理给 ReferencedBeanInvocationHandler（反射调用 demeServiceImpl 中方法）
            return newProxyInstance(getClassLoader(), new Class[]{serviceInterfaceType},
                    newReferencedBeanInvocationHandler(referencedBeanName));
        } else {
            // 暴露协议为inJvm的的时候需要立马暴露，调用服务虽然也是本地，但是需要走filter链
            // 1. ServiceBean 在本地并且 @DubboReference(injvm = false),那么就会调用 ServiceBean 的 exporter 走 filter链
            // https://dubbo.apache.org/zh/docs/v2.7/user/examples/local-call/#%E9%85%8D%E7%BD%AE
            // 如果存在本地 ServiceBean 就立马将它暴露，如果不在这里暴露，那么后面创建 reference 代理的时候就会报错
            // no provider avliable
            exportServiceBeanIfNecessary(referencedBeanName); // If the referenced ServiceBean exits, export it immediately
            // 2. 其他正常的远程调用
            // 同一个 referenceBean 调用两次是否会产生两个代理 ？
            // see : org.apache.dubbo.demo.consumer.comp.DemoServiceComponent
            return referenceBean.get();
        }
    }


    private void exportServiceBeanIfNecessary(String referencedBeanName) {
        if (existsServiceBean(referencedBeanName)) {
            ServiceBean serviceBean = getServiceBean(referencedBeanName);
            if (!serviceBean.isExported()) {
                serviceBean.export();
            }
        }
    }

    private ServiceBean getServiceBean(String referencedBeanName) {
        return applicationContext.getBean(referencedBeanName, ServiceBean.class);
    }

    private InvocationHandler newReferencedBeanInvocationHandler(String referencedBeanName) {
        return referencedBeanInvocationHandlersCache.computeIfAbsent(referencedBeanName,
                ReferencedBeanInvocationHandler::new);
    }

    /**
     * The {@link InvocationHandler} class for the referenced Bean
     */
    @Override
    public void onApplicationEvent(ServiceBeanExportedEvent event) {
        initReferencedBeanInvocationHandler(event.getServiceBean());
    }

    private void initReferencedBeanInvocationHandler(ServiceBean serviceBean) {
        String serviceBeanName = serviceBean.getBeanName();
        referencedBeanInvocationHandlersCache.computeIfPresent(serviceBeanName, (name, handler) -> {
            handler.init();
            return null;
        });
    }

    private class ReferencedBeanInvocationHandler implements InvocationHandler {

        private final String referencedBeanName;

        private Object bean;

        private ReferencedBeanInvocationHandler(String referencedBeanName) {
            this.referencedBeanName = referencedBeanName;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = null;
            try {
                if (bean == null) {
                    init();
                }
                result = method.invoke(bean, args);
            } catch (InvocationTargetException e) {
                // re-throws the actual Exception.
                throw e.getTargetException();
            }
            return result;
        }

        private void init() {
            ServiceBean serviceBean = applicationContext.getBean(referencedBeanName, ServiceBean.class);
            this.bean = serviceBean.getRef();
        }
    }

    @Override
    protected String buildInjectedObjectCacheKey(AnnotationAttributes attributes, Object bean, String beanName,
                                                 Class<?> injectedType, InjectionMetadata.InjectedElement injectedElement) {
        return buildReferencedBeanName(attributes, injectedType) +
                "#source=" + (injectedElement.getMember()) +
                "#attributes=" + getAttributes(attributes, getEnvironment());
    }

    /**
     * @param attributes           the attributes of {@link Reference @Reference}
     * @param serviceInterfaceType the type of Dubbo's service interface
     * @return The name of bean that annotated Dubbo's {@link Service @Service} in local Spring {@link ApplicationContext}
     */
    private String buildReferencedBeanName(AnnotationAttributes attributes, Class<?> serviceInterfaceType) {
        ServiceBeanNameBuilder serviceBeanNameBuilder = create(attributes, serviceInterfaceType, getEnvironment());
        return serviceBeanNameBuilder.build();
    }

    private ReferenceBean buildReferenceBeanIfAbsent(String referenceBeanName, AnnotationAttributes attributes,
                                                     Class<?> referencedType)
            throws Exception {
        ReferenceBean<?> referenceBean = referenceBeanCache.get(referenceBeanName);

        if (referenceBean == null) {
            ReferenceBeanBuilder beanBuilder = ReferenceBeanBuilder
                    .create(attributes, applicationContext)
                    .interfaceClass(referencedType);
            referenceBean = beanBuilder.build();
            referenceBeanCache.put(referenceBeanName, referenceBean);
        } else if (!referencedType.isAssignableFrom(referenceBean.getInterfaceClass())) {
            throw new IllegalArgumentException("reference bean name " + referenceBeanName + " has been duplicated, but interfaceClass " +
                    referenceBean.getInterfaceClass().getName() + " cannot be assigned to " + referencedType.getName());
        }
        return referenceBean;
    }

    private void cacheInjectedReferenceBean(ReferenceBean referenceBean,
                                            InjectionMetadata.InjectedElement injectedElement) {
        if (injectedElement.getMember() instanceof Field) {
            injectedFieldReferenceBeanCache.put(injectedElement, referenceBean);
        } else if (injectedElement.getMember() instanceof Method) {
            injectedMethodReferenceBeanCache.put(injectedElement, referenceBean);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void destroy() throws Exception {
        super.destroy();
        this.referenceBeanCache.clear();
        this.referencedBeanInvocationHandlersCache.clear();
        this.injectedFieldReferenceBeanCache.clear();
        this.injectedMethodReferenceBeanCache.clear();
    }
}
