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
package org.apache.dubbo.monitor.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.monitor.Monitor;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.monitor.support.AbstractMonitorFactory;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;

import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_PROTOCOL;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REFERENCE_FILTER_KEY;
import static org.apache.dubbo.remoting.Constants.CHECK_KEY;

/**
 * DefaultMonitorFactory
 */
public class DubboMonitorFactory extends AbstractMonitorFactory {

    private Protocol protocol;

    private ProxyFactory proxyFactory;

    //SPI注入
    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    //SPI注入
    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    protected Monitor createMonitor(URL url) {
        URLBuilder urlBuilder = URLBuilder.from(url);
        //将MonitorUrl中原来protocol的参数值Registry改为dubbo
        //原来的参数值在<dubbo:monitor protocol="registry">中配置表示从注册中心中发现监控中心实例
        urlBuilder.setProtocol(url.getParameter(PROTOCOL_KEY, DUBBO_PROTOCOL));
        if (StringUtils.isEmpty(url.getPath())) {
            urlBuilder.setPath(MonitorService.class.getName());
        }
        String filter = url.getParameter(REFERENCE_FILTER_KEY);
        if (StringUtils.isEmpty(filter)) {
            filter = "";
        } else {
            filter = filter + ",";
        }
        //这里的dubboMonitor相当于是MonitorService服务的consumer端，类似于服务引用过程
        //dubboMonitor作为一个dubbo的客户端，在引用远端服务MonitorService的时候 去除monitorFilter过滤器
        urlBuilder.addParameters(CHECK_KEY, String.valueOf(false),
                REFERENCE_FILTER_KEY, filter + "-monitor");
        //引用MonitorService（远端监控中心），得到consumer端的invoker
        Invoker<MonitorService> monitorInvoker = protocol.refer(MonitorService.class, urlBuilder.build());
        //创建远程MonitorService服务的代理，类似于dubbo consumer 引用 dubbo 服务 monitorService（监控中心）
        MonitorService monitorService = proxyFactory.getProxy(monitorInvoker);
        return new DubboMonitor(monitorInvoker, monitorService);
    }

}
