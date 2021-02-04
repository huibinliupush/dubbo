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

import org.apache.dubbo.config.AbstractInterfaceConfig;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.RegistryConfig;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.util.Assert;

import java.lang.annotation.Annotation;
import java.util.List;

import static com.alibaba.spring.util.BeanFactoryUtils.getBeans;
import static com.alibaba.spring.util.BeanFactoryUtils.getOptionalBean;

/**
 * An Abstract Builder to build {@link AbstractInterfaceConfig Interface Config} Bean that annotated
 * some {@link Annotation annotation}.
 *
 * @see ReferenceBeanBuilder
 * @see AbstractInterfaceConfig
 * @see AnnotationAttributes
 * @since 2.7.3
 */
public abstract class AnnotatedInterfaceConfigBeanBuilder<C extends AbstractInterfaceConfig> {

    protected final Log logger = LogFactory.getLog(getClass());

    protected final AnnotationAttributes attributes;

    protected final ApplicationContext applicationContext;

    protected final ClassLoader classLoader;

    protected Object configBean;

    protected Class<?> interfaceClass;

    protected AnnotatedInterfaceConfigBeanBuilder(AnnotationAttributes attributes, ApplicationContext applicationContext) {
        Assert.notNull(attributes, "The Annotation attributes must not be null!");
        Assert.notNull(applicationContext, "The ApplicationContext must not be null!");
        this.attributes = attributes;
        this.applicationContext = applicationContext;
        this.classLoader = applicationContext.getClassLoader() != null ?
                applicationContext.getClassLoader() : Thread.currentThread().getContextClassLoader();
    }

    /**
     * Build {@link C}
     *
     * @return non-null
     * @throws Exception
     */
    public final C build() throws Exception {

        checkDependencies();

        //new出一个 ReferenceBean
        C configBean = doBuild();
        //根据@reference注解信息构造ReferenceBean
        //(注解里配置的属性就装配，没有标注的就不装配，自动装配在后续的ReferenceBean.init()中实现)
        configureBean(configBean);

        if (logger.isInfoEnabled()) {
            logger.info("The configBean[type:" + configBean.getClass().getSimpleName() + "] has been built.");
        }

        return configBean;

    }

    private void checkDependencies() {

    }

    /**
     * Builds {@link C Bean}
     *
     * @return {@link C Bean}
     */
    protected abstract C doBuild();


    protected void configureBean(C configBean) throws Exception {
        //根据注解中的attributes(@reference) 装配cofigBean(ReferenceBean)
        preConfigureBean(attributes, configBean);
        //获取注解中配置的String[] registry
        configureRegistryConfigs(configBean);
        //获取注解配置的 String monitor()
        configureMonitorConfig(configBean);
        //获取注解配置的String application()
        configureApplicationConfig(configBean);
        //获取注解配置的String module()
        configureModuleConfig(configBean);
        //配置bean的interface consumer method
        //最后调用bean的afterPropertiesSet()初始化bean
        postConfigureBean(attributes, configBean);

    }

    protected abstract void preConfigureBean(AnnotationAttributes attributes, C configBean) throws Exception;


    private void configureRegistryConfigs(C configBean) {
        //获取注解中配置的String[] registry
        String[] registryConfigBeanIds = resolveRegistryConfigBeanNames(attributes);
        //根据注解中配置的 registryConfigBeanId 查找RegistryConfig Bean
        List<RegistryConfig> registryConfigs = getBeans(applicationContext, registryConfigBeanIds, RegistryConfig.class);

        configBean.setRegistries(registryConfigs);

    }

    private void configureMonitorConfig(C configBean) {

        String monitorBeanName = resolveMonitorConfigBeanName(attributes);

        MonitorConfig monitorConfig = getOptionalBean(applicationContext, monitorBeanName, MonitorConfig.class);

        configBean.setMonitor(monitorConfig);

    }

    private void configureApplicationConfig(C configBean) {

        String applicationConfigBeanName = resolveApplicationConfigBeanName(attributes);

        ApplicationConfig applicationConfig =
                getOptionalBean(applicationContext, applicationConfigBeanName, ApplicationConfig.class);

        configBean.setApplication(applicationConfig);

    }

    private void configureModuleConfig(C configBean) {

        String moduleConfigBeanName = resolveModuleConfigBeanName(attributes);

        ModuleConfig moduleConfig =
                getOptionalBean(applicationContext, moduleConfigBeanName, ModuleConfig.class);

        configBean.setModule(moduleConfig);

    }

    /**
     * Resolves the configBean name of {@link ModuleConfig}
     *
     * @param attributes {@link AnnotationAttributes}
     * @return
     */
    protected abstract String resolveModuleConfigBeanName(AnnotationAttributes attributes);

    /**
     * Resolves the configBean name of {@link ApplicationConfig}
     *
     * @param attributes {@link AnnotationAttributes}
     * @return
     */
    protected abstract String resolveApplicationConfigBeanName(AnnotationAttributes attributes);


    /**
     * Resolves the configBean ids of {@link RegistryConfig}
     *
     * @param attributes {@link AnnotationAttributes}
     * @return non-empty array
     */
    protected abstract String[] resolveRegistryConfigBeanNames(AnnotationAttributes attributes);

    /**
     * Resolves the configBean name of {@link MonitorConfig}
     *
     * @param attributes {@link AnnotationAttributes}
     * @return
     */
    protected abstract String resolveMonitorConfigBeanName(AnnotationAttributes attributes);

    /**
     * Configures Bean
     *
     * @param attributes
     * @param configBean
     */
    protected abstract void postConfigureBean(AnnotationAttributes attributes, C configBean) throws Exception;


    public <T extends AnnotatedInterfaceConfigBeanBuilder<C>> T configBean(Object configBean) {
        this.configBean = configBean;
        return (T) this;
    }

    public <T extends AnnotatedInterfaceConfigBeanBuilder<C>> T interfaceClass(Class<?> interfaceClass) {
        this.interfaceClass = interfaceClass;
        return (T) this;
    }
}
