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
package org.apache.dubbo.config.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Parameter 用于将config bean中的属性添加到URL中
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Parameter {

    /**
     * 属性添加到URL中的Key 如果没有则从get方法中提取属性名称
     * */
    String key() default "";

    boolean required() default false;

    /**
     * 等于true的话 则该属性 不会添加到Url中。
     * */
    boolean excluded() default false;

    /**
     *    if (parameter != null && parameter.escaped()) {
     *         str = URL.encode(str);
     *    }
     * */
    boolean escaped() default false;

    boolean attribute() default false;

    /**
     *      if (parameter != null && parameter.append()) {
     *          String pre = parameters.get(key);
     *          if (pre != null && pre.length() > 0) {
     *              str = pre + "," + str;
     *          }
     *      }
     *      属性值要加上前缀key
     * */
    boolean append() default false;

    /**
     * 解析该注解方法
     * org.apache.dubbo.config.AbstractConfig#appendParameters(java.util.Map, java.lang.Object, java.lang.String)
     *
     * if {@link #key()} is specified, it will be used as the key for the annotated property when generating url.
     * by default, this key will also be used to retrieve the config value:
     * <pre>
     * {@code
     *  class ExampleConfig {
     *      // Dubbo will try to get "dubbo.example.alias_for_item=xxx" from .properties, if you want to use the original property
     *      // "dubbo.example.item=xxx", you need to set useKeyAsProperty=false.
     *      @Parameter(key = "alias_for_item")
     *      public getItem();
     *  }
     * }
     *
     * </pre>
     */
    boolean useKeyAsProperty() default true;

}