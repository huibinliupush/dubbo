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
package org.apache.dubbo.demo.consumer.comp;

import org.apache.dubbo.config.annotation.Argument;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.Method;
import org.apache.dubbo.demo.BigDeDto;
import org.apache.dubbo.demo.CallbackListener;
import org.apache.dubbo.demo.CallbackService;
import org.apache.dubbo.demo.DemoService;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component("demoServiceComponent")
public class DemoServiceComponent implements DemoService {
    @DubboReference(check = false,
            methods = { @Method(name = "sayHello", timeout = 250, retries = 3) }) // , mock = "force:return fake"
    // parameters = {"sayHello.mock","force:return fake"}) 这里有 bug,解析异常。会把 ： 替换为 ,
    // see org.apache.dubbo.config.spring.beans.factory.annotation.ReferenceBeanBuilder.preConfigureBean
    private DemoService demoService; // 同一字段放在不同类中也是不同的代理 ？ 错，还是一个代理

    @DubboReference(check = false )
    private CallbackService callbackService;

    private DemoService demoService1;

    // @DubboReference 标注在方法上以及标注在字段上，虽然注解属性一样，依赖注入的类型一样，但是其实背后注入的是两个代理
    // 但其实这里没必要，只要依赖注入的类型一样，那么背后就都是一个代理 ？错，还是一个代理
    // 因为只要依赖注入的类型一样，@DubboReference 的属性一样，就是一个 ReferenceBean
    // 同一个 ReferenceBean 调用两次都会返回同一个代理，代理会被缓存在 ReferenceBean->ref 字段中
    @DubboReference(check = false)
    public void setDemoService(DemoService demoService) { // 必须是 public get or set method 才能依赖注入
        this.demoService1 = demoService;
    }

    @Override
    public String sayHello(String name) {
        return demoService.sayHello(name);
    }

    @Override
    public CompletableFuture<String> sayHelloAsync(String name) {
        return demoService.sayHelloAsync(name);
    }

    @Override
    public void testBigDecimal(BigDeDto bigDeDto) {
        demoService.testBigDecimal(bigDeDto);
    }

    @Override
    public void wrapperReturnVoid(String warpperField) {

    }

    @Override
    public DemoService wrapperReturnVoid(Integer warpperField) {
        return null;
    }

    public void addListener(String key, CallbackListener listener) {
        // 在发起远程调用的时候，encode 阶段，会生成 CallbackListener 的 export (并不会 openServer，而是复用 client 连接)
        // org.apache.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.exportOrUnexportCallbackService

        // provider 在收到 addListener 的调用请求之后，会在解码阶段（decodeHandle）中为参数 listener 生成 reference 代理
        // org.apache.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.referOrDestroyCallbackService
        callbackService.addListener(key,listener);
    }
}
