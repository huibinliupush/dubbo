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
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.event.ServiceConfigExportedEvent;
import org.apache.dubbo.config.event.ServiceConfigUnexportedEvent;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.event.Event;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.metadata.WritableMetadataService;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_IP_TO_BIND;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.METADATA_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_BIND;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.MULTICAST;
import static org.apache.dubbo.config.Constants.SCOPE_NONE;
import static org.apache.dubbo.remoting.Constants.BIND_IP_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.PROXY_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_LOCAL;
import static org.apache.dubbo.rpc.Constants.SCOPE_REMOTE;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;

public class ServiceConfig<T> extends ServiceConfigBase<T> {

    public static final Logger logger = LoggerFactory.getLogger(ServiceConfig.class);

    /**
     * A random port cache, the different protocols who has no port specified have different random port
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    /**
     * A delayed exposure service timer
     */
    private static final ScheduledExecutorService DELAY_EXPORT_EXECUTOR = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));
    //这里的PROTOCOL是Protocol接口的适配器
    private static final Protocol PROTOCOL = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its
     * default implementation
     */
    //ProxyFactory接口的适配器
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * Whether the provider has been exported
     */
    private transient volatile boolean exported;

    /**
     * The flag whether a service has unexported ,if the method unexported is invoked, the value is true
     */
    private transient volatile boolean unexported;

    private DubboBootstrap bootstrap;

    /**
     * The exported services
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        super(service);
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    public void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("Unexpected error occured when unexport " + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;

        // dispatch a ServiceConfigUnExportedEvent since 2.7.4
        dispatch(new ServiceConfigUnexportedEvent(this));
    }

    public synchronized void export() {
        //根据<dubbo:service export=".."> 和 <dubbo:provider export="...">配置决定是否暴露服务
        if (!shouldExport()) {
            return;
        }

        if (bootstrap == null) {
            bootstrap = DubboBootstrap.getInstance();
            bootstrap.init();
        }

        // 1. 填充 service 中没有配置的属性，默认填充方式： provider > module > application
        // 2. 按照配置源的优先级重新设置 ServiceConfig 的属性,得到最终的 ServiceConfig 配置
        // 配置优先级：-D 系统变量 > 环境变量 > 外部化配置 > XML，注解，API设置的配置 > 本地配置文件 dubbo.properties
        checkAndUpdateSubConfigs(); // 按照配置源优先级，最终确定 serverConfig 的配置，以及校验相关配置的合法性（格式，扩展点）

        //init serviceMetadata
        //设置服务元信息，后续会在dubbo自省架构中详细论述
        serviceMetadata.setVersion(version);
        serviceMetadata.setGroup(group);
        serviceMetadata.setDefaultGroup(group);
        serviceMetadata.setServiceType(getInterfaceClass());
        serviceMetadata.setServiceInterfaceName(getInterface());
        serviceMetadata.setTarget(getRef());

        //根据<dubbo:service delay=".."> 和 <dubbo:provider delay="...">配置决定是否延迟暴露服务
        if (shouldDelay()) {
            DELAY_EXPORT_EXECUTOR.schedule(this::doExport, getDelay(), TimeUnit.MILLISECONDS);
        } else {
            //暴露服务
            doExport();
        }
        //发布服务暴露事件ServiceConfigExportedEvent
        exported();
    }

    public void exported() {
        // dispatch a ServiceConfigExportedEvent since 2.7.4
        dispatch(new ServiceConfigExportedEvent(this));
    }

    /**
     * 设置默认配置，并根据配置源的优先级顺序依次覆盖得到最终配置
     * 配置优先级：-D 系统变量 > 环境变量 > 外部化配置 > XML，注解，API设置的配置 > 本地配置文件 dubbo.properties
     * */
    private void checkAndUpdateSubConfigs() {
        // Use default configs defined explicitly with global scope
        //设置默认配置，缺省配置 按照配置的provider配置设置。优先级<dubbo:method> > <dubbo:service> > <dubbo:provider> > <dubbo:module> > <dubbo:appliction>
        //服务消费者配置的优先级 > 服务提供者
        completeCompoundConfigs();
        //设置默认provider配置
        checkDefault();
        //设置protocolConfig（按照配置源的优先级加载），refresh Protocol config
        checkProtocol();
        // init some null configuration.
        // 回调配置处理前置处理器
        // dubbo框架没有默认实现，用户可自定义扩展。
        // 实现ConfigInitializer.class接口，定义SPI文件
        List<ConfigInitializer> configInitializers = ExtensionLoader.getExtensionLoader(ConfigInitializer.class)
                .getActivateExtension(URL.valueOf("configInitializer://"), (String[]) null);
        configInitializers.forEach(e -> e.initServiceConfig(this));

        // if protocol is not injvm checkRegistry
        if (!isOnlyInJvm()) {
            //检查<dubbo:service />中的registry（值为注册中心<dubbo:registry />中的id或者name
            //将配置的中的registryIds转换成为RegistryConfig
            // refresh Registry config
            checkRegistry();
        }

        /**
         *
         * 到现在，serviceConfig 中的全部属性就按照 service > provider > module > application 的优先级填充好了
         * service 中没有配置的属性，默认填充方式： provider > module > application
         * 以上就是 serviceConfig 本地配置的终级版，下面就是按照配置源的优先级重新设置 ServiceConfig 的属性
         * 配置优先级：-D 系统变量 > 环境变量 > 外部化配置 > XML，注解，API设置的配置 > 本地配置文件 dubbo.properties
         * */
        //根据属性配置源优先级，重新按照优先级获取属性配置，设置ServiceBean的属性 , refresh 其他基本属性
        this.refresh();

        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        //处理泛化实现配置(泛化实现是需要向客户端提供API的，客户端正常API调用，服务端用泛化的方式实现)
        //https://dubbo.apache.org/zh/docs/v2.7/user/examples/generic-service/
        //与泛化实现对应的泛化调用，在服务引用的时候会分析
        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                generic = Boolean.TRUE.toString();
            }
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //检查如果dubbo配置了Method相关：<dubbo:method />，检查method配置的合理性
            // refresh MethodConfig
            checkInterfaceAndMethods(interfaceClass, getMethods());
            //检查ref是否实现了interfaceClass接口
            checkRef();
            generic = Boolean.FALSE.toString();
        }
        //已废弃
        if (local != null) {
            if ("true".equals(local)) {
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                localClass = ClassUtils.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        //https://dubbo.apache.org/zh/docs/v2.7/user/examples/local-stub/
        //检查本地存根相关配置，检查stub实现类
        if (stub != null) {
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                //stub实现类是否存在
                stubClass = ClassUtils.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //stub实现类是否继承了InterfaceClass
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        //stub实现类是否实现了interfaceClass接口，并且是否包含可传入Proxy（消费者端会传入service的远程代理）的构造函数，构造器参数为interfaceClass
        //stub由服务端实现，打包放在api jar包中 (客户端提供也可以)然后在客户端执行。客户端创建远程服务代理proxy通过 stub实现类的构造函数传递给stub实现类。
        //客户端实际引用的就是stub实现类，然后stub实现类包装了远程服务代理proxy。
        checkStubAndLocal(interfaceClass);
        //https://dubbo.apache.org/zh/docs/v2.7/user/examples/local-mock/
        //检查Mock配置的有效性
        ConfigValidationUtils.checkMock(interfaceClass, this);
        //检查ServiceConfig 也就是<dubbo:service />中相关配置的有效性
        // 检查所有配置的相关扩展点是否已经加载
        ConfigValidationUtils.validateServiceConfig(this);
        //回调配置后置处理器（用户可通过SPI自定义扩展 配置后置处理器）
        postProcessConfig();
    }


    protected synchronized void doExport() {
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        if (exported) {
            return;
        }
        exported = true;

        if (StringUtils.isEmpty(path)) {
            path = interfaceName;
        }
        doExportUrls();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        //服务元数据存储
        ServiceRepository repository = ApplicationModel.getServiceRepository();
        //注册服务的元数据
        ServiceDescriptor serviceDescriptor = repository.registerService(getInterfaceClass());
        //注册providerModel元数据
        repository.registerProvider(
                getUniqueServiceName(),
                ref,
                serviceDescriptor,
                this,
                serviceMetadata
        );

        //加载注册中心URLs(将RegistryConfig转换为URL)，dubbo支持多注册中心,一个服务接口可以同时注册到多个不同的注册中心。
        //registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&metadata-type=remote&pid=2044&qos.port=22222&registry=zookeeper&timestamp=1615790840656
        // registryURL 包含 applicationConfig , registryConfig, 以及 RuntimeParameters 等参数
        List<URL> registryURLs = ConfigValidationUtils.loadRegistries(this, true);

        //dubbo支持多协议暴露，同一个服务接口可以暴露多种协议，这里根据配置的服务协议依次暴露服务
        //<dubbo:protocol id="dubbo" contextpath="servicePathPrefix" name="dubbo" port="20880" />
        //<dubbo:service interface="org.apache.dubbo.demo.DemoService" path="servicePath" registry="zookeeper" ref="demoService"/>
        for (ProtocolConfig protocolConfig : protocols) {
            //构建暴露服务的serviceKey格式 group/contextPath/path:version
            //servicePathPrefix/servicePath
            String pathKey = URL.buildKey(getContextPath(protocolConfig)
                    .map(p -> p + "/" + path)
                    .orElse(path), group, version);
            // In case user specified path, register service one more time to map it to path.
            repository.registerService(pathKey, interfaceClass);
            // TODO, uncomment this line once service key is unified
            serviceMetadata.setServiceKey(pathKey);
            //暴露服务，并将服务注册到配置的所有注册中心上
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    // 按照配置项的优先级生成服务 url
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        String name = protocolConfig.getName();
        //默认协议为dubbo
        if (StringUtils.isEmpty(name)) {
            name = DUBBO;
        }

        Map<String, String> map = new HashMap<String, String>();
        map.put(SIDE_KEY, PROVIDER_SIDE);
        //添加服务运行时信息 dubbo-version, release,timstamp,pid
        ServiceConfig.appendRuntimeParameters(map);
        //将dubbo config bean中的配置信息 添加到URL参数中，根据配置的优先级依次覆盖
        AbstractConfig.appendParameters(map, getMetrics());
        AbstractConfig.appendParameters(map, getApplication());
        AbstractConfig.appendParameters(map, getModule());
        // remove 'default.' prefix for configs from ProviderConfig
        // appendParameters(map, provider, Constants.DEFAULT_KEY);
        AbstractConfig.appendParameters(map, provider);
        AbstractConfig.appendParameters(map, protocolConfig);
        //设置<dubbo:service />相关配置
        AbstractConfig.appendParameters(map, this);
        MetadataReportConfig metadataReportConfig = getMetadataReportConfig();
        // 如果配置了元数据中心，则元数据远程上报，否则本地上报
        if (metadataReportConfig != null && metadataReportConfig.isValid()) {
            //添加元数据Report类型metadata-type（服务元数据上报相关）
            map.putIfAbsent(METADATA_KEY, REMOTE_METADATA_STORAGE_TYPE);
        }

        /**
         *     将<dubbo:method />配置写入URL,接下来主要用来处理如下这种配置方式
         *     <dubbo:service interface="org.apache.dubbo.samples.callback.api.CallbackService" ref="callbackService"
         *                    connections="1" callbacks="1000">
         *         <dubbo:method name="addListener">
         *             <dubbo:argument index="1" type="org.apache.dubbo.samples.callback.api.CallbackListener" callback="true"/>
         *         </dubbo:method>
         *     </dubbo:service>
         *
         * */
        if (CollectionUtils.isNotEmpty(getMethods())) {
            for (MethodConfig method : getMethods()) {
                //将method的相关配置加入URL中，参数Key前缀为methodName
                AbstractConfig.appendParameters(map, method, method.getName());

                //兼容老版本过期配置retry 将retry变为retries
                //retry Deprecated. Replace to retries
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                //将<dubbo:argument />配置写入URL,主要用来配置参数回调
                List<ArgumentConfig> arguments = method.getArguments();
                if (CollectionUtils.isNotEmpty(arguments)) {
                    for (ArgumentConfig argument : arguments) {
                        // convert argument type
                        // 处理<dubbo:argument type="..."/>
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            if (methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    if (methodName.equals(method.getName())) {
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        //处理index和type同时配置的情况<dubbo:argument index=".." type="..."/>
                                        if (argument.getIndex() != -1) {
                                            //检查index中配置的参数类型 与type中指定的参数类型是否一致。
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                //ArgumentConfig相关的属性在URL中的参数key为 需要加上前缀：methodName.argumentIndex
                                                //addListener.1.callback -> true
                                                AbstractConfig.appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // multiple callbacks in the method
                                            //处理只配置type的情况<dubbo:argument type="..."/>
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                //根据配置的目标参数类型找到 方法中的参数 并获取到参数在方法上的index
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    //ArgumentConfig相关的属性在URL中的参数key为 需要加上前缀：methodName.argumentIndex
                                                    AbstractConfig.appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) {//处理<dubbo:argument index="..."/>
                            AbstractConfig.appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException("Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }

        if (ProtocolUtils.isGeneric(generic)) {
            map.put(GENERIC_KEY, generic);
            map.put(METHODS_KEY, ANY_VALUE);
        } else {
            //dubbo自省架构中服务注册模型中的概念，后续会深入解析。
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put(REVISION_KEY, revision);
            }
            //获取暴露接口的方法名称集合（支持方法继承）
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                //添加方法名称到URL中
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }

        /**
         * Here the token value configured by the provider is used to assign the value to ServiceConfig#token
         *  <dubbo:service interface="org.apache.dubbo.demo.DemoService" token="..." ref="demoService"/>
         *  <dubbo:provider token="..."/>
         */
        if(ConfigUtils.isEmpty(token) && provider != null) {
            token = provider.getToken();
        }

        // token配置值为true或者default时 token默认为随机UUID
        // token配置了具体的字符串，就将配置的字符串作为token
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                map.put(TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(TOKEN_KEY, token);
            }
        }
        //init serviceMetadata attachments
        serviceMetadata.getAttachments().putAll(map);

        // export service
        String host = findConfigedHosts(protocolConfig, registryURLs, map);
        Integer port = findConfigedPorts(protocolConfig, name, map);
        //<dubbo:protocol id="dubbo" contextpath="servicePathPrefix" name="dubbo" port="20880" />
        //<dubbo:service interface="org.apache.dubbo.demo.DemoService" path="servicePath"  ref="demoService"/>
        //url格式： 协议://host:port/contextpatcj/path?服务参数=参数值&......
        // path 用来指定 URL 的 path , 默认为 interfaceName
        URL url = new URL(name, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), map);
        //dubbo://10.52.38.28:20880/servicePathPrefix/org.apache.dubbo.demo.provider.api.CallbackService?addListener.1.callback=true&anyhost=true&application=demo-provider&bind.ip=10.52.38.28&bind.port=20880&callbacks=1000&connections=1&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.provider.api.CallbackService&metadata-type=remote&methods=addListener&pid=5148&qos.port=22222&release=&side=provider&timestamp=1615865248401
        // You can customize Configurator to append extra parameters
        //通过SPI加载Configurator扩展（自定义URL参数配置扩展）
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            // 从配置中心覆盖 URL
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        String scope = url.getParameter(SCOPE_KEY);
        /**
         * 远程发布暴露 port （注册或者不注册）, （本地发布不会暴露 port ,consumer不可直连）
         * <dubbo:service scope="..." />
         * scope可选值：local remote none 默认为Null
         * null：既要远程发布（注册到注册中心）也要本地发布（不注册服务，consumer不可直连，本地调用也需要走invoker链）
         * none：不进行发布。相当于只是本地起了个普通service服务。自然调用也不会走Invoker链
         * !remote: 本地发布
         * !local：远程发布
         * */
        if (!SCOPE_NONE.equalsIgnoreCase(scope)) {

            // export to local if the config is not remote (export to remote only when config is remote)
            if (!SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                //本地发布 直接调用protocol层的InjvmProtocol进行本地发布
                exportLocal(url);
            }
            // export to remote if the config is not local (export to local only when config is local)
            //远程发布
            if (!SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                if (CollectionUtils.isNotEmpty(registryURLs)) {
                    //将服务依次向多个注册中心注册
                    for (URL registryURL : registryURLs) {
                        //if protocol is only injvm ,not register
                        //当协议为injvm时跳过，因为scope默认为Null 之前上边代码已经本地发布过了，这里不需要在进行本地发布
                        if (LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
                            continue;
                        }
                        //dynamic为true自动注册服务，下线服务
                        //dynamic为false人工手动注册服务，下线服务
                        url = url.addParameterIfAbsent(DYNAMIC_KEY, registryURL.getParameter(DYNAMIC_KEY));
                        //根据monitorConfig配置加载monitorUrl，加载过程类似loadRegistries
                        //<dubbo:monitor protocol="registry" interval="100"/>
                        URL monitorUrl = ConfigValidationUtils.loadMonitor(this, registryURL);
                        if (monitorUrl != null) {
                            //在monitorFilter中会用到，用于上报监控数据到监控中心MonitorService
                            //监控中心其实就是一个dubbo服务，服务接口是MonitorService。用户可以自己实现这个MonitorService，然后将其
                            //暴露为dubbo服务，这就是监控中心，
                            url = url.addParameterAndEncoded(MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            if (url.getParameter(REGISTER_KEY, true)) {
                                logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                            } else {
                                logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                            }
                        }

                        // For providers, this is used to enable custom proxy to generate invoker
                        //获取proxy扩展名，后续用于SPI加载proxy代理扩展
                        String proxy = url.getParameter(PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            registryURL = registryURL.addParameter(PROXY_KEY, proxy);
                        }
                        //通过prroxyFactory创建invoker，服务发布proxy层入口
                        //为服务实现类的对象ref创建相应的Invoker
                        //将服务URL添加到RegistryUrl中的export参数中（用于后续在regitry层进行服务发布）

                        /**
                         * 注意这里：如果一个服务需要注册到多个注册中心，也就是说有多个 registryURLs 的情况下
                         * invoker 也会是多个，一个 invoker 对应一个 registryURL
                         * 但其背后的代理 Wrapper 都是同一个。
                         *
                         * */
                        Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(EXPORT_KEY, url.toFullString()));
                        //包装关联invoker和serviceConfig
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                        //根据协议头Registry 通过SPI加载protocol扩展RegistryProtocol(服务发布registry层入口)
                        //invoker转为exporter
                        //这里的PROTOCOL是Protocol接口的适配器
                        Exporter<?> exporter = PROTOCOL.export(wrapperInvoker);
                        //缓存暴露的exporter
                        exporters.add(exporter);
                    }
                } else {
                    //处理非injvm协议（dubbo协议）但没有配置注册中心的情况。仅发布服务，不注册服务但consumer可以直连
                    if (logger.isInfoEnabled()) {
                        logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                    }
                    // 只发布不注册的话，这里的 url 是 service 的。dubbo://hot:port/interfacename?参数=值
                    // 远程发布的话，这里的 url 是 registry 的。registry://hot:port/interfacename?参数=值
                    Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
                    //通过PROTOCOL接口适配器加载DubboProtocol直接发布dubbo服务
                    Exporter<?> exporter = PROTOCOL.export(wrapperInvoker);
                    exporters.add(exporter);
                }
                /**
                 * @since 2.7.0
                 * ServiceData Store
                 * 存储服务元数据
                 */
                WritableMetadataService metadataService = WritableMetadataService.getExtension(url.getParameter(METADATA_KEY, DEFAULT_METADATA_STORAGE_TYPE));
                if (metadataService != null) {
                    metadataService.publishServiceDefinition(url);
                }
            }
        }
        this.urls.add(url);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * always export injvm
     */
    private void exportLocal(URL url) {
        URL local = URLBuilder.from(url)
                .setProtocol(LOCAL_PROTOCOL)
                .setHost(LOCALHOST_VALUE)
                .setPort(0)
                .build();
        Exporter<?> exporter = PROTOCOL.export(
                PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, local));
        exporters.add(exporter);
        logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry url : " + local);
    }

    /**
     * Determine if it is injvm
     *
     * @return
     */
    private boolean isOnlyInJvm() {
        return getProtocols().size() == 1
                && LOCAL_PROTOCOL.equalsIgnoreCase(getProtocols().get(0).getName());
    }


    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig,
                                     List<URL> registryURLs,
                                     Map<String, String> map) {
        boolean anyhost = false;

        String hostToBind = getValueFromConfig(protocolConfig, DUBBO_IP_TO_BIND);
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (StringUtils.isEmpty(hostToBind)) {
            hostToBind = protocolConfig.getHost();
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                try {
                    logger.info("No valid ip found from environment, try to find valid host from DNS.");
                    hostToBind = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                if (isInvalidLocalHost(hostToBind)) {
                    if (CollectionUtils.isNotEmpty(registryURLs)) {
                        for (URL registryURL : registryURLs) {
                            if (MULTICAST.equalsIgnoreCase(registryURL.getParameter("registry"))) {
                                // skip multicast registry since we cannot connect to it via Socket
                                continue;
                            }
                            try (Socket socket = new Socket()) {
                                SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                socket.connect(addr, 1000);
                                hostToBind = socket.getLocalAddress().getHostAddress();
                                break;
                            } catch (Exception e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                    if (isInvalidLocalHost(hostToBind)) {
                        hostToBind = getLocalHost();
                    }
                }
            }
        }

        map.put(BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        String hostToRegistry = getValueFromConfig(protocolConfig, DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }


    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @return
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig,
                                      String name,
                                      Map<String, String> map) {
        Integer portToBind = null;

        // parse bind port from environment
        String port = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            if (portToBind <= 0) {
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
            }
        }

        // save bind port, used as url's key later
        map.put(BIND_PORT_KEY, String.valueOf(portToBind));

        // registry port, not used as bind port by default
        String portToRegistryStr = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        return portToRegistry;
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String value = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(value)) {
            value = ConfigUtils.getSystemProperty(key);
        }
        return value;
    }

    private Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        return RANDOM_PORT_MAP.getOrDefault(protocol, Integer.MIN_VALUE);
    }

    private void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
            logger.warn("Use random available port(" + port + ") for protocol " + protocol);
        }
    }

    private void postProcessConfig() {
        //dubbo框架没有默认实现，用户可自定义扩展。
        //实现ConfigPostProcessor.class接口，定义SPI文件
        List<ConfigPostProcessor> configPostProcessors =ExtensionLoader.getExtensionLoader(ConfigPostProcessor.class)
                .getActivateExtension(URL.valueOf("configPostProcessor://"), (String[]) null);
        configPostProcessors.forEach(component -> component.postProcessServiceConfig(this));
    }

    /**
     * Dispatch an {@link Event event}
     *
     * @param event an {@link Event event}
     * @since 2.7.5
     */
    private void dispatch(Event event) {
        EventDispatcher.getDefaultExtension().dispatch(event);
    }

    public DubboBootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(DubboBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }
}
