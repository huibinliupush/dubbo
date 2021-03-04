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
package org.apache.dubbo.config.spring.schema;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.AbstractServiceConfig;
import org.apache.dubbo.config.ArgumentConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.ServiceBean;
import org.apache.dubbo.config.spring.beans.factory.annotation.DubboConfigAliasPostProcessor;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.core.env.Environment;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static com.alibaba.spring.util.BeanRegistrar.registerInfrastructureBean;
import static org.apache.dubbo.common.constants.CommonConstants.HIDE_KEY_PREFIX;

/**
 * AbstractBeanDefinitionParser
 *
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
    private static final Pattern GROUP_AND_VERSION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    private static final String ONRETURN = "onreturn";
    private static final String ONTHROW = "onthrow";
    private static final String ONINVOKE = "oninvoke";
    private static final String METHOD = "Method";
    private final Class<?> beanClass;
    private final boolean required;

    public DubboBeanDefinitionParser(Class<?> beanClass, boolean required) {
        this.beanClass = beanClass;
        this.required = required;
    }

    @SuppressWarnings("unchecked")
    private static BeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean required) {
        //创建beanClass对应的beanDefinition
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        beanDefinition.setLazyInit(false);
        String id = resolveAttribute(element, "id", parserContext);
        //如果标签元素element中没有定义id属性，并且required=true 则为beanDefintion自动生成id属性
        if (StringUtils.isEmpty(id) && required) {
            //取出标签中的name属性
            String generatedBeanName = resolveAttribute(element, "name", parserContext);
            if (StringUtils.isEmpty(generatedBeanName)) {
                //对于<protocal>标签来说默认的beanName为dubbo
                if (ProtocolConfig.class.equals(beanClass)) {
                    generatedBeanName = "dubbo";
                } else {
                    //其他标签用interface属性作为beanName
                    generatedBeanName = resolveAttribute(element, "interface", parserContext);
                }
            }
            if (StringUtils.isEmpty(generatedBeanName)) {
                //标签中没有指定interface属性则用标签对应的beanClassName作为beanName
                generatedBeanName = beanClass.getName();
            }
            id = generatedBeanName;
            int counter = 2;
            //避免注册重复的beanDefinition
            while (parserContext.getRegistry().containsBeanDefinition(id)) {
                id = generatedBeanName + (counter++);
            }
        }
        //注册beanClass的beanDefinition,并且设置id属性
        if (StringUtils.isNotEmpty(id)) {
            if (parserContext.getRegistry().containsBeanDefinition(id)) {
                throw new IllegalStateException("Duplicate spring bean id " + id);
            }
            //****注册beanDefinition
            parserContext.getRegistry().registerBeanDefinition(id, beanDefinition);
            beanDefinition.getPropertyValues().addPropertyValue("id", id);
        }
        if (ProtocolConfig.class.equals(beanClass)) {
            //主要处理这种情况，标签<dubbo:service> 先引用到<dubbo:protocol>对应的bean,先解析<dubbo:service>标签但是对应的
            //ServiceBean属性protocol应该引用的是ProtocolConfig，但这时<dubbo:protocol>还没有被解析，那么就占时将字面值“dubbo”存起来
            //等到后续解析标签<dubbo:protocol>时候 在将ServiceBeanDefinition中的protocol替换成RuntimeBeanReference引用。
            //<dubbo:service interface="org.apache.dubbo.demo.DemoService" protocol="dubbo" ref="demoService"/>
            //<dubbo:protocol id="dubbo" name="dubbo" port="20880"/>
            //这里的逻辑主要针对老版本的处理，2.7.7版本这块逻辑不会走到，新版本protocol支持多协议，protocol属性处理在下边
            //新版本的protocol是存放在BeanDefinition中的protocolIds属性中。
            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
                //遍历Spring中注册的所有BeanDefinition，查看BeanDefinition是否具有Protocol属性
                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);
                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
                if (property != null) {
                    //如果该bean先于protocol加载并且配置了protocol的字面值，那么在这里就会将Protocol的字面值替换成引用RuntimeBeanReference
                    Object value = property.getValue();
                    if (value instanceof ProtocolConfig && id.equals(((ProtocolConfig) value).getName())) {
                        definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(id));
                    }
                }
            }
        } else if (ServiceBean.class.equals(beanClass)) {
            //处理<dubbo:service>标签中配置class这种特殊情况
            //<dubbo:service interface="org.apache.dubbo.demo.DemoService" class="org.apache.dubbo.demo.provider.DemoServiceImpl"/>
            //获取标签中配置的class属性
            String className = resolveAttribute(element, "class", parserContext);
            if (StringUtils.isNotEmpty(className)) {
                //创建DemoServiceImpl类的BeanDefinition
                RootBeanDefinition classDefinition = new RootBeanDefinition();
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                classDefinition.setLazyInit(false);
                //解析<property>标签中的属性,将标签中配置的name value(ref) 添加到DemoServiceImpl类的BeanDefinition中。
                //设置classDefinition属性 这里需要注意的是<dubbo:service>中的<property>标签设置的是class类的属性并不是ServceBean的属性
                parseProperties(element.getChildNodes(), classDefinition, parserContext);
                //自动配置<dubbo:service>标签的ref属性，并添加到标签对应的dubbo config beanDefinition中
                beanDefinition.getPropertyValues().addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, id + "Impl"));
            }
        }  else if (ProviderConfig.class.equals(beanClass)) {
            /**
             * 这里主要处理<dubbo:provider/>标签内部嵌套<dubbo:service />标签的情况
             * 将provider设置到service中的provider属性中
             *    <dubbo:provider id="defaultDubboCofig" protocol="dubbo,hessian" registry="beijingRegistry,shanghaiRegistry">
             *         <dubbo:service interface="org.apache.dubbo.samples.multi.registry.api.HelloService" ref="helloService"/>
             *    </dubbo:provider>
             * */
            parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
        } else if (ConsumerConfig.class.equals(beanClass)) {
            /**
             * 这里主要处理<dubbo:consumer/>标签内部嵌套<dubbo:reference />标签的情况
             * 将consumer设置到reference中的consumer属性中
             *     <dubbo:consumer registry="shanghaiRegistry">
             *         <dubbo:reference id="helloServiceFormShanghai"
             *                          interface="org.apache.dubbo.samples.multi.registry.api.HelloService"/>
             *     </dubbo:consumer>
             * */
            parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
        }
        //处理标签属性通用逻辑
        //提取标签属性
        Set<String> props = new HashSet<>();
        ManagedMap parameters = null;
        //遍历标签对应的dubbo config bean中所有set方法 用来提取标签属性名称。
        for (Method setter : beanClass.getMethods()) {
            String name = setter.getName();
            //set方法条件：1：public方法  2.只有一个参数
            if (name.length() > 3 && name.startsWith("set")
                    && Modifier.isPublic(setter.getModifiers())
                    && setter.getParameterTypes().length == 1) {
                //提取参数类型（这里指标签设置的属性 对应的java类型）
                Class<?> type = setter.getParameterTypes()[0];
                //获取bean中的属性名称（去掉set,后边第一个字符转小写）setNameAddress -> nameAddress
                String beanProperty = name.substring(3, 4).toLowerCase() + name.substring(4);
                //获取标签对应的属性名称(驼峰命名转为 -隔开)  nameAddress -> name-address
                String property = StringUtils.camelToSplitName(beanProperty, "-");
                //property是属性在XML中的名称
                //beanProperty是属性在dubbo config bean中的名称
                props.add(property);
                // check the setter/getter whether match
                Method getter = null;
                //检查是否有set对应的get方法或者is方法（boolean类型）
                try {
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try {
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                        // ignore, there is no need any log here since some class implement the interface: EnvironmentAware,
                        // ApplicationAware, etc. They only have setter method, otherwise will cause the error log during application start up.
                    }
                }
                //不存在get或者is方法代表 这个解析出来的beanProperty并不是 需要设置的属性
                if (getter == null
                        || !Modifier.isPublic(getter.getModifiers())
                        || !type.equals(getter.getReturnType())) {
                    continue;
                }

                if ("parameters".equals(property)) {
                    //解析嵌套标签<dubbo:parameter />
                    parameters = parseParameters(element.getChildNodes(), beanDefinition, parserContext);
                } else if ("methods".equals(property)) {
                    //解析嵌套标签<dubbo:method /> 将解析后的List<MethodConfig>设置到beanDefintion中的methods属性中
                    parseMethods(id, element.getChildNodes(), beanDefinition, parserContext);
                } else if ("arguments".equals(property)) {
                    //解析嵌套标签<dubbo:argument /> 将解析后的List<ArgumentConfig>设置到beanDefintion中的arguments属性中
                    parseArguments(id, element.getChildNodes(), beanDefinition, parserContext);
                } else {
                    //获取标签属性property 对应的值
                    String value = resolveAttribute(element, property, parserContext);
                    if (value != null) {
                        value = value.trim();
                        if (value.length() > 0) {
                            if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
                                RegistryConfig registryConfig = new RegistryConfig();
                                registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
                                beanDefinition.getPropertyValues().addPropertyValue(beanProperty, registryConfig);
                            } else if ("provider".equals(property) || "registry".equals(property) || ("protocol".equals(property) && AbstractServiceConfig.class.isAssignableFrom(beanClass))) {
                                /**
                                 * For 'provider' 'protocol' 'registry', keep literal value (should be id/name) and set the value to 'registryIds' 'providerIds' protocolIds'
                                 * The following process should make sure each id refers to the corresponding instance, here's how to find the instance for different use cases:
                                 * 1. Spring, check existing bean by id, see{@link ServiceBean#afterPropertiesSet()}; then try to use id to find configs defined in remote Config Center
                                 * 2. API, directly use id to find configs defined in remote Config Center; if all config instances are defined locally, please use {@link ServiceConfig#setRegistries(List)}
                                 */
                                //针对标签中设置的属性为provider,registry,protocol情况的处理。这里需要注意在XML配置中 这三个属性配置的值必须是对应标签的id或者name
                                //这三种标签在对应的dubbo config bean中的属性名称是providerIds，registryIds，protocolIds
                                //dubbo支持多提供者，多注册中心，多协议
                                beanDefinition.getPropertyValues().addPropertyValue(beanProperty + "Ids", value);
                            } else {
                                Object reference;
                                //处理标签属性对应的java类型为基础类型的情况
                                if (isPrimitive(type)) {
                                    if ("async".equals(property) && "false".equals(value)
                                            || "timeout".equals(property) && "0".equals(value)
                                            || "delay".equals(property) && "0".equals(value)
                                            || "version".equals(property) && "0.0.0".equals(value)
                                            || "stat".equals(property) && "-1".equals(value)
                                            || "reliable".equals(property) && "false".equals(value)) {
                                        // backward compatibility for the default value in old version's xsd
                                        value = null;
                                    }
                                    reference = value;
                                } else if (ONRETURN.equals(property) || ONTHROW.equals(property) || ONINVOKE.equals(property)) {
                                    /**
                                     *  处理dubbo事件通知配置
                                     *  <bean id ="demoCallback" class = "org.apache.dubbo.callback.implicit.NotifyImpl" />
                                     * <dubbo:reference id="demoService" interface="org.apache.dubbo.callback.implicit.IDemoService" version="1.0.0" group="cn" >
                                     *       <dubbo:method name="get" async="true" onreturn = "demoCallback.onreturn" onthrow="demoCallback.onthrow" />
                                     * </dubbo:reference>
                                     * https://dubbo.apache.org/zh/docs/v2.7/user/examples/events-notify/
                                     * */
                                    int index = value.lastIndexOf(".");
                                    //事件通知处理bean
                                    String ref = value.substring(0, index);
                                    //通知事件的处理方法
                                    String method = value.substring(index + 1);
                                    //beanDefinition中设置事件通知处理类的ref引用
                                    reference = new RuntimeBeanReference(ref);
                                    //beanDefinition中设置onreturnMethod等事件通知方法属性
                                    beanDefinition.getPropertyValues().addPropertyValue(property + METHOD, method);
                                } else {
                                    //处理标签属性对应的Java类型为 引用类型的情况
                                    if ("ref".equals(property) && parserContext.getRegistry().containsBeanDefinition(value)) {
                                        BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
                                        if (!refBean.isSingleton()) {
                                            throw new IllegalStateException("The exported service ref " + value + " must be singleton! Please set the " + value + " bean scope to singleton, eg: <bean id=\"" + value + "\" scope=\"singleton\" ...>");
                                        }
                                    }
                                    reference = new RuntimeBeanReference(value);
                                }
                                //通过property从标签中查找XML配置的属性值.
                                //通过beanProperty属性将XML配置的属性值设置到beanDefinition中。
                                beanDefinition.getPropertyValues().addPropertyValue(beanProperty, reference);
                            }
                        }
                    }
                }
            }
        }
        //将beanClass中不包含的属性 全部设置到paramers中
        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            Node node = attributes.item(i);
            String name = node.getLocalName();
            if (!props.contains(name)) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String value = node.getNodeValue();
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }
        if (parameters != null) {
            //将解析到的parameters（ManagedMap类型）设置到BeanDefinition中的parameters
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }
        return beanDefinition;
    }

    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class
                || cls == Character.class || cls == Short.class || cls == Integer.class
                || cls == Long.class || cls == Float.class || cls == Double.class
                || cls == String.class || cls == Date.class || cls == Class.class;
    }

    /**
     *  解析内嵌标签
     *  element：父标签元素
     *  parserContext：DOM解析上下文
     *  beanClass：内嵌标签对应的dubbo config bean
     *  required: 是否需要自动生成beanId
     *  tag: 内嵌标签元素名称
     *  property：父标签Bean 被内嵌标签bean中的属性Property所引用 (内嵌标签Bean要引用父标签bean)
     *  ref: 父标签beanId
     *  beanDefinition: 父标签所对应的dubbo config beanDefinition
     * */
    private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean required, String tag, String property, String ref, BeanDefinition beanDefinition) {
        NodeList nodeList = element.getChildNodes();
        if (nodeList == null) {
            return;
        }
        boolean first = true;
        //遍历父标签的嵌套子标签
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            //tag为嵌套标签名称，匹配指定的嵌套标签
            if (tag.equals(node.getNodeName())
                    || tag.equals(node.getLocalName())) {
                if (first) {
                    first = false;
                    //设置父标签的default属性 比如<dubbo:provider>标签的default属性 默认false 是否为缺省协议，用于多协议
                    String isDefault = resolveAttribute(element, "default", parserContext);
                    if (StringUtils.isEmpty(isDefault)) {
                        beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                    }
                }
                //解析嵌套标签为对应的dubbo config beanDefinition
                BeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, required);
                if (subDefinition != null && StringUtils.isNotEmpty(ref)) {
                    //嵌套标签中的proterty属性用来引用父标签对应的configBean
                    subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
                }
            }
        }
    }

    /**
     * 解析<property></property>标签中的属性
     * <property name="demoDAO" value="demoDAO" /
     * 当配置的是value时，则向BeanDefinition中添加name value(字面值)
     * <property name="demoDAO" ref="demoDAO" /
     * 当配置的是ref时，则向BeanDefinition中添加name new RuntimeBeanReference(ref)
     * */
    private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        if (nodeList == null) {
            return;
        }
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("property".equals(element.getNodeName())
                    || "property".equals(element.getLocalName())) {
                String name = resolveAttribute(element, "name", parserContext);
                if (StringUtils.isNotEmpty(name)) {
                    String value = resolveAttribute(element, "value", parserContext);
                    String ref = resolveAttribute(element, "ref", parserContext);
                    if (StringUtils.isNotEmpty(value)) {
                        beanDefinition.getPropertyValues().addPropertyValue(name, value);
                    } else if (StringUtils.isNotEmpty(ref)) {
                        beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));
                    } else {
                        throw new UnsupportedOperationException("Unsupported <property name=\"" + name + "\"> sub tag, Only supported <property name=\"" + name + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        if (nodeList == null) {
            return null;
        }
        ManagedMap parameters = null;
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("parameter".equals(element.getNodeName())
                    || "parameter".equals(element.getLocalName())) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String key = resolveAttribute(element, "key", parserContext);
                String value = resolveAttribute(element, "value", parserContext);
                boolean hide = "true".equals(resolveAttribute(element, "hide", parserContext));
                if (hide) {
                    key = HIDE_KEY_PREFIX + key;
                }
                parameters.put(key, new TypedStringValue(value, String.class));
            }
        }
        return parameters;
    }

    @SuppressWarnings("unchecked")
    private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                     ParserContext parserContext) {
        if (nodeList == null) {
            return;
        }
        ManagedList methods = null;
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("method".equals(element.getNodeName()) || "method".equals(element.getLocalName())) {
                String methodName = resolveAttribute(element, "name", parserContext);
                if (StringUtils.isEmpty(methodName)) {
                    throw new IllegalStateException("<dubbo:method> name attribute == null");
                }
                if (methods == null) {
                    methods = new ManagedList();
                }
                BeanDefinition methodBeanDefinition = parse(element,
                        parserContext, MethodConfig.class, false);
                String name = id + "." + methodName;
                BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(
                        methodBeanDefinition, name);
                methods.add(methodBeanDefinitionHolder);
            }
        }
        if (methods != null) {
            beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
        }
    }

    @SuppressWarnings("unchecked")
    private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                       ParserContext parserContext) {
        if (nodeList == null) {
            return;
        }
        ManagedList arguments = null;
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("argument".equals(element.getNodeName()) || "argument".equals(element.getLocalName())) {
                String argumentIndex = resolveAttribute(element, "index", parserContext);
                if (arguments == null) {
                    arguments = new ManagedList();
                }
                BeanDefinition argumentBeanDefinition = parse(element,
                        parserContext, ArgumentConfig.class, false);
                String name = id + "." + argumentIndex;
                BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(
                        argumentBeanDefinition, name);
                arguments.add(argumentBeanDefinitionHolder);
            }
        }
        if (arguments != null) {
            beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
        }
    }

    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        // Register DubboConfigAliasPostProcessor
        //用AbstractConfig#getId()作为dubbo config bean 的别名
        registerDubboConfigAliasPostProcessor(parserContext.getRegistry());

        return parse(element, parserContext, beanClass, required);
    }

    /**
     * Register {@link DubboConfigAliasPostProcessor}
     *
     * @param registry {@link BeanDefinitionRegistry}
     * @since 2.7.5 [Feature] https://github.com/apache/dubbo/issues/5093
     */
    private void registerDubboConfigAliasPostProcessor(BeanDefinitionRegistry registry) {
        registerInfrastructureBean(registry, DubboConfigAliasPostProcessor.BEAN_NAME, DubboConfigAliasPostProcessor.class);
    }

    private static String resolveAttribute(Element element, String attributeName, ParserContext parserContext) {
        String attributeValue = element.getAttribute(attributeName);
        Environment environment = parserContext.getReaderContext().getEnvironment();
        return environment.resolvePlaceholders(attributeValue);
    }
}
