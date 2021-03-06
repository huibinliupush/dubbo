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
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ServiceMetadata;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;

/**
 * ServiceConfig
 *
 * @export
 */
public abstract class ServiceConfigBase<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;

    /**
     * The interface name of the exported service
     */
    protected String interfaceName;

    /**
     * The interface class of the exported service
     */
    protected Class<?> interfaceClass;

    /**
     * The reference of the interface implementation
     */
    protected T ref;

    /**
     * The service name
     */
    protected String path;

    /**
     * The provider configuration
     */
    protected ProviderConfig provider;

    /**
     * The providerIds
     */
    protected String providerIds;

    /**
     * whether it is a GenericService
     */
    protected volatile String generic;

    protected ServiceMetadata serviceMetadata;

    public ServiceConfigBase() {
        serviceMetadata = new ServiceMetadata();
        serviceMetadata.addAttribute("ORIGIN_CONFIG", this);
    }

    public ServiceConfigBase(Service service) {
        serviceMetadata = new ServiceMetadata();
        serviceMetadata.addAttribute("ORIGIN_CONFIG", this);
        appendAnnotation(Service.class, service);
        setMethods(MethodConfig.constructMethodConfig(service.methods()));
    }

    @Deprecated
    private static List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (CollectionUtils.isEmpty(providers)) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    private static List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (CollectionUtils.isEmpty(protocols)) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    @Deprecated
    private static ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    private static ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    public boolean shouldExport() {
        Boolean export = getExport();
        // default value is true
        return export == null ? true : export;
    }

    @Override
    public Boolean getExport() {
        return (export == null && provider != null) ? provider.getExport() : export;
    }

    public boolean shouldDelay() {
        Integer delay = getDelay();
        return delay != null && delay > 0;
    }

    @Override
    public Integer getDelay() {
        return (delay == null && provider != null) ? provider.getDelay() : delay;
    }

    public void checkRef() {
        // reference should not be null, and is the implementation of the given interface
        if (ref == null) {
            throw new IllegalStateException("ref not allow null!");
        }
        if (!interfaceClass.isInstance(ref)) {
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public Optional<String> getContextPath(ProtocolConfig protocolConfig) {
        String contextPath = protocolConfig.getContextpath();
        if (StringUtils.isEmpty(contextPath) && provider != null) {
            contextPath = provider.getContextpath();
        }
        return Optional.ofNullable(contextPath);
    }

    protected Class getServiceClass(T ref) {
        return ref.getClass();
    }

    public void checkDefault() throws IllegalStateException {
        if (provider == null) {
            provider = ApplicationModel.getConfigManager()
                    .getDefaultProvider()
                    .orElse(new ProviderConfig());
        }
    }

    public void checkProtocol() {
        //如果<dubbo:service/>中没有配置protocols 但配置了provider,则先用provider中的Protocols配置作为默认配置
        if (CollectionUtils.isEmpty(protocols) && provider != null) {
            setProtocols(provider.getProtocols());
        }
        //按照配置优先级加载Protocol配置，并将protocolIds转为ProtocolConfig
        convertProtocolIdsToProtocols();
    }

    public void completeCompoundConfigs() {
        //根据优先级设置默认的配置
        super.completeCompoundConfigs(provider);
        //如果配置了<dubbo:provider />,则根据provider的配置作为默认配置
        if (provider != null) {
            if (protocols == null) {
                setProtocols(provider.getProtocols());
            }
            if (configCenter == null) {
                setConfigCenter(provider.getConfigCenter());
            }
            if (StringUtils.isEmpty(registryIds)) {
                setRegistryIds(provider.getRegistryIds());
            }
            if (StringUtils.isEmpty(protocolIds)) {
                setProtocolIds(provider.getProtocolIds());
            }
        }
    }

    private void convertProtocolIdsToProtocols() {
        //默认采用provider配置中的protocol属性
        computeValidProtocolIds();
        //<dubbo:service interface="org.apache.dubbo.demo.DemoService"  ref="demoService"/>
        // 如果service没有配置protocol属性，则采用全局procotol属性<dubbo:protocol id="dubbo" name="dubbo" port="20880"/>
        if (StringUtils.isEmpty(protocolIds)) {
            //<dubbo:service/>中没有配置protocol,也没有配置provider。或者配置了provider，但provider没有配置protocol
            if (CollectionUtils.isEmpty(protocols)) {
                //查询<dubbo:protocol>全局配置
                List<ProtocolConfig> protocolConfigs = ApplicationModel.getConfigManager().getDefaultProtocols();
                if (protocolConfigs.isEmpty()) {
                    //如果全局也没有配置<dubbo:protocol />则从不同的配置源中按照优先级加载protocol配置
                    protocolConfigs = new ArrayList<>(1);
                    ProtocolConfig protocolConfig = new ProtocolConfig();
                    protocolConfig.setDefault(true);
                    //根据不同配置数据源的优先级 加载配置
                    protocolConfig.refresh();
                    protocolConfigs.add(protocolConfig);
                    ApplicationModel.getConfigManager().addProtocol(protocolConfig);
                }
                //这种情况下 如果配置了<dubbo:protocol/> 则使用全局配置
                setProtocols(protocolConfigs);
            }
        } else {
            //<dubbo:service interface="org.apache.dubbo.demo.DemoService" protocol="dubbo" ref="demoService"/>
            //service配置了protocol，则通过configManager加载全局配置<dubbo:protocol id="dubbo" name="dubbo" port="20880"/>
            String[] arr = COMMA_SPLIT_PATTERN.split(protocolIds);
            List<ProtocolConfig> tmpProtocols = new ArrayList<>();
            Arrays.stream(arr).forEach(id -> {
                if (tmpProtocols.stream().noneMatch(prot -> prot.getId().equals(id))) {
                    // <dubbo:protocol id="dubbo" name="dubbo" port="20880"/>
                    //通过configManager获取全局protocol配置
                    Optional<ProtocolConfig> globalProtocol = ApplicationModel.getConfigManager().getProtocol(id);
                    if (globalProtocol.isPresent()) {
                        tmpProtocols.add(globalProtocol.get());
                    } else {
                        ProtocolConfig protocolConfig = new ProtocolConfig();
                        protocolConfig.setId(id);
                        ////根据不同配置数据源的优先级 加载配置
                        protocolConfig.refresh();
                        tmpProtocols.add(protocolConfig);
                    }
                }
            });
            if (tmpProtocols.size() > arr.length) {
                throw new IllegalStateException("Too much protocols found, the protocols comply to this service are :" + protocolIds + " but got " + protocols
                        .size() + " registries!");
            }
            setProtocols(tmpProtocols);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (StringUtils.isEmpty(id)) {
            id = interfaceName;
        }
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    public void setProvider(ProviderConfig provider) {
        ApplicationModel.getConfigManager().addProvider(provider);
        this.provider = provider;
    }

    @Parameter(excluded = true)
    public String getProviderIds() {
        return providerIds;
    }

    public void setProviderIds(String providerIds) {
        this.providerIds = providerIds;
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isValidGenericValue(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    @Override
    public void setMock(String mock) {
        throw new IllegalArgumentException("mock doesn't support on provider side");
    }

    @Override
    public void setMock(Object mock) {
        throw new IllegalArgumentException("mock doesn't support on provider side");
    }

    public ServiceMetadata getServiceMetadata() {
        return serviceMetadata;
    }

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
    }

    @Override
    @Parameter(excluded = true)
    public String getPrefix() {
        return DUBBO + ".service." + interfaceName;
    }

    @Parameter(excluded = true)
    public String getUniqueServiceName() {
        String group = StringUtils.isEmpty(this.group) ? provider.getGroup() : this.group;
        String version = StringUtils.isEmpty(this.version) ? provider.getVersion() : this.version;
        return URL.buildKey(interfaceName, group, version);
    }

    /**
     * 如果<dubbo:service /> 没有配置protocol，并且配置了provider 则默认护采用Provider中的配置
     * getProtocolIds() 获取<dubbo:service />中的配置的protocol
     * */
    private void computeValidProtocolIds() {
        if (StringUtils.isEmpty(getProtocolIds())) {
            if (getProvider() != null && StringUtils.isNotEmpty(getProvider().getProtocolIds())) {
                setProtocolIds(getProvider().getProtocolIds());
            }
        }
    }

    @Override
    protected void computeValidRegistryIds() {
        super.computeValidRegistryIds();
        if (StringUtils.isEmpty(getRegistryIds())) {
            if (getProvider() != null && StringUtils.isNotEmpty(getProvider().getRegistryIds())) {
                setRegistryIds(getProvider().getRegistryIds());
            }
        }
    }

    public abstract void export();

    public abstract void unexport();

    public abstract boolean isExported();

    public abstract boolean isUnexported();

}