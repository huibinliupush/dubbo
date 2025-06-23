# spring boot 自动装配流程：

## 1.定义 spring.factories 文件，导入自动配置类

spring.factories 是 Spring Boot 自动配置机制的核心配置文件，它采用 Java SPI（Service Provider Interface）机制实现模块化自动配置，是 Spring Boot "约定优于配置"理念的关键实现。

1. **启动阶段**：Spring Boot 应用启动时，`SpringApplication` 会通过 `SpringFactoriesLoader` 加载 `META-INF/spring.factories` 文件中声明的所有自动配置类。

2. **条件过滤**：加载的自动配置类会经过条件注解（如 `@ConditionalOnClass`）的过滤，只有满足条件的配置类才会生效。

3. **Bean注册**：生效的配置类中定义的 Bean 会被注册到 Spring 容器中。

Spring Boot 支持多种类型的扩展，以下是一些常用的键，以下接口均可以在 spring.factories 文件中配置（类似 SPI）

- **`org.springframework.boot.autoconfigure.EnableAutoConfiguration`**：自动配置类列表（最常用）。

- **`org.springframework.context.ApplicationContextInitializer`**：应用上下文初始化器。

- **`org.springframework.context.ApplicationListener`**：应用事件监听器。

- **`org.springframework.boot.env.EnvironmentPostProcessor`**：环境变量后处理器（用于在应用启动前修改环境变量）。

- **`org.springframework.boot.diagnostics.FailureAnalyzer`**：启动失败分析器（用于提供启动失败时的友好错误信息）。

- **`org.springframework.boot.autoconfigure.template.TemplateAvailabilityProvider`**：模板可用性提供者（用于检查模板引擎是否存在）。

## 2.  org.springframework.boot.autoconfigure.AutoConfiguration.imports 文件

AutoConfiguration.imports 是 Spring Boot 3.0 引入的新一代自动配置注册文件，用于取代传统的 spring.factories 方式，提供更简洁、高效的自动配置机制。这是 Spring Boot 在自动配置领域的重要演进。

这个文件是Spring Boot 3.0中用于自动配置类注册的推荐方式，它取代了之前`spring.factories`中`EnableAutoConfiguration`键的列表。

自动配置加载流程：

sequenceDiagram
participant A as SpringApplication
participant B as AutoConfigurationLoader
participant C as AutoConfigurationImports

    A->>B: 启动时调用load()
    B->>C: 扫描所有AutoConfiguration.imports文件
    C->>C: 收集所有自动配置类
    C->>B: 返回类名列表
    B->>B: 应用条件注解过滤
    B->>A: 返回有效配置类
    A->>A: 注册配置类到容器

## 3. 定义 additional-spring-configuration-metadata.json 文件

additional-spring-configuration-metadata.json 是 Spring Boot 中用于增强配置元数据的关键文件，它为自定义配置属性提供丰富的元信息，显著提升开发体验和配置的可维护性
用于提供自定义配置属性的元数据，以增强IDE支持和配置提示。

- IDE 智能提示：在 IntelliJ IDEA、VSCode 等 IDE 中提供配置属性的自动补全

- 配置文档：在 IDE 中显示配置属性的描述信息

- 类型提示：明确配置属性的数据类型和格式

- 默认值展示：显示属性的默认值

- 值集限定：提供可选值范围

使用 spring-boot-configuration-processor 依赖自动生成基础元数据：

```
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>

spring-boot-configuration-processor 会扫描 @ConfigurationProperties 注解的类（DubboConfigurationProperties）
```

```
@ConfigurationProperties(prefix = "my.service")
public class MyProperties {
    private boolean enabled = true;
    private Duration timeout = Duration.ofSeconds(30);
    private int maxAttempts = 3;

    // getter/setter
}
```

    A[源码中的 @ConfigurationProperties 类] --> B[配置处理器]
    B --> C[生成 spring-configuration-metadata.json]
    D[开发者编写的 additional-spring-configuration-metadata.json] --> E[合并]
    C --> E
    E --> F[最终元数据]
    F --> G[IDE 提示]

# Dubbo Spring Boot Auto-Configure

`dubbo-spring-boot-autoconfigure` uses Spring Boot's `@EnableAutoConfiguration` which helps core Dubbo's components to be auto-configured by `DubboAutoConfiguration`. It reduces code, eliminates XML configuration.



## Content

1. [Main Content](https://github.com/apache/dubbo-spring-boot-project)
2. [Integrate with Maven](#integrate-with-maven)
3. [Auto Configuration](#auto-configuration)
4. [Externalized Configuration](#externalized-configuration)
5. [Dubbo Annotation-Driven (Chinese)](http://dubbo.apache.org/zh-cn/blog/dubbo-annotation-driven.html)
6. [Dubbo Externalized Configuration (Chinese)](http://dubbo.apache.org/zh-cn/blog/dubbo-externalized-configuration.html)



## Integrate with Maven

You can introduce the latest `dubbo-spring-boot-autoconfigure` to your project by adding the following dependency to your pom.xml

```xml
<dependency>
    <groupId>org.apache.dubbo</groupId>
    <artifactId>dubbo-spring-boot-autoconfigure</artifactId>
    <version>2.7.4.1</version>
</dependency>
```

If your project failed to resolve the dependency, try to add the following repository:
```xml
<repositories>
    <repository>
        <id>apache.snapshots.https</id>
        <name>Apache Development Snapshot Repository</name>
        <url>https://repository.apache.org/content/repositories/snapshots</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```



## Auto Configuration

Since  `2.5.7`  , Dubbo totally supports Annotation-Driven , core Dubbo's components that are registered and initialized in  Spring application context , including externalized configuration features. However , those features need to trigger in manual configuration , e.g `@DubboComponentScan` , `@EnableDubboConfig` or `@EnableDubbo`.

> If you'd like to learn more , please read [Dubbo Annotation-Driven (Chinese)](http://dubbo.apache.org/zh-cn/blog/dubbo-annotation-driven.html)



`dubbo-spring-boot-autoconfigure` uses Spring Boot's `@EnableAutoConfiguration` which helps core Dubbo's components to be auto-configured by `DubboAutoConfiguration`. It reduces code, eliminates XML configuration.



## Externalized Configuration

Externalized Configuration is a core feature of Spring Boot , Dubbo Spring Boot not only supports it definitely , but also inherits Dubbo's Externalized Configuration, thus it provides single and multiple Dubbo's `*Config` Bindings from `PropertySources` , and `"dubbo."` is a common prefix of property name.

> If you'd like to learn more , please read [Dubbo Externalized Configuration](http://dubbo.apache.org/zh-cn/blog/dubbo-externalized-configuration.html)(Chinese).



### Single Dubbo Config Bean Bindings

In most use scenarios , "Single Dubbo Config Bean Bindings" is enough , because a Dubbo application only requires single Bean of `*Config` (e.g `ApplicationConfig`). You add properties in `application.properties` to configure Dubbo's `*Config` Beans that you want , be like this :

```properties
dubbo.application.name = foo
dubbo.application.owner = bar
dubbo.registry.address = 10.20.153.10:9090
```

There are two Spring Beans will be initialized when Spring `ApplicationContext` is ready, their Bean types are `ApplicationConfig` and `RegistryConfig`.



#### Getting Single Dubbo Config Bean

 If application requires current `ApplicationConfig` Bean in somewhere , you can get it from Spring `BeanFactory` as those code :

```java
BeanFactory beanFactory = ....
ApplicationConfig applicationConfig = beanFactory.getBean(ApplicationConfig.class)
```

or inject it :

```java
@Autowired
private ApplicationConfig application;
```



#### Identifying Single Dubbo Config Bean

If you'd like to identify this `ApplicationConfig` Bean , you could add **"id"** property:

```properties
dubbo.application.id = application-bean-id
```



#### Mapping Single Dubbo Config Bean

The whole Properties Mapping of "Single Dubbo Config Bean Bindings" lists below :

| Dubbo `*Config` Type | The prefix of property name for Single Bindings |
| -------------------- | ---------------------------------------- |
| `ProtocolConfig`     | `dubbo.protocol`                         |
| `ApplicationConfig`  | `dubbo.application`                      |
| `ModuleConfig`       | `dubbo.module`                           |
| `RegistryConfig`     | `dubbo.registry`                         |
| `MonitorConfig`      | `dubbo.monitor`                          |
| `ProviderConfig`     | `dubbo.provider`                         |
| `ConsumerConfig`     | `dubbo.consumer`                         |



An example properties :

```properties
# Single Dubbo Config Bindings
## ApplicationConfig
dubbo.application.id = applicationBean
dubbo.application.name = dubbo-demo-application

## ModuleConfig
dubbo.module.id = moduleBean
dubbo.module.name = dubbo-demo-module

## RegistryConfig
dubbo.registry.address = zookeeper://192.168.99.100:32770

## ProtocolConfig
dubbo.protocol.name = dubbo
dubbo.protocol.port = 20880

## MonitorConfig
dubbo.monitor.address = zookeeper://127.0.0.1:32770

## ProviderConfig
dubbo.provider.host = 127.0.0.1

## ConsumerConfig
dubbo.consumer.client = netty
```



### Multiple Dubbo Config Bean Bindings

In contrast , "Multiple Dubbo Config Bean Bindings" means Externalized Configuration will be used to configure multiple Dubbo `*Config` Beans.



#### Getting Multiple Dubbo Config Bean

The whole Properties Mapping of "Multiple Dubbo Config Bean Bindings" lists below :

| Dubbo `*Config` Type | The prefix of property name for Multiple Bindings |
| -------------------- | ---------------------------------------- |
| `ProtocolConfig`     | `dubbo.protocols`                        |
| `ApplicationConfig`  | `dubbo.applications`                     |
| `ModuleConfig`       | `dubbo.modules`                          |
| `RegistryConfig`     | `dubbo.registries`                       |
| `MonitorConfig`      | `dubbo.monitors`                         |
| `ProviderConfig`     | `dubbo.providers`                        |
| `ConsumerConfig`     | `dubbo.consumers`                        |



#### Identifying Multiple Dubbo Config Bean

There is a  different way to identify Multiple Dubbo Config Bean , the configuration pattern is like this :

`${config-property-prefix}.${config-bean-id}.${property-name} = some value` , let's explain those placeholders :

- `${config-property-prefix}` : The The prefix of property name for Multiple Bindings , e.g. `dubbo.protocols`, `dubbo.applications` and so on.
- `${config-bean-id}` : The bean id of Dubbo's `*Config`
- `${property-name}`: The property name of  `*Config`

An example properties :

```properties
dubbo.applications.application1.name = dubbo-demo-application
dubbo.applications.application2.name = dubbo-demo-application2
dubbo.modules.module1.name = dubbo-demo-module
dubbo.registries.registry1.address = zookeeper://192.168.99.100:32770
dubbo.protocols.protocol1.name = dubbo
dubbo.protocols.protocol1.port = 20880
dubbo.monitors.monitor1.address = zookeeper://127.0.0.1:32770
dubbo.providers.provider1.host = 127.0.0.1
dubbo.consumers.consumer1.client = netty
```



### IDE Support



If you used advanced IDE tools , for instance [Jetbrains IDEA Ultimate](https://www.jetbrains.com/idea/) develops Dubbo Spring Boot application, it will popup the tips of Dubbo Configuration Bindings in `application.properties` :



#### Case 1 - Single Bindings

![](config-popup-window.png)



#### Case 2 - Multiple Bindings

![](mconfig-popup-window.png)

​

