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
package org.apache.dubbo.springboot.demo.provider;

import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.springboot.demo.User;

import java.util.HashMap;
import java.util.List;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@DubboService
public class RestDemoServiceImpl implements RestTestService {

    private static final String PREFIX = "Hello ";

    @Override
    public String getMuchParam(String id, String name) {
        return PREFIX + id + " " + name;
    }

    @Override
    public String getMuchVariable(String id, String name) {
        return PREFIX + id + " " + name;
    }

    @Override
    public String getReg(String name, String version, String ext) {
        return PREFIX + name + " " + version + " " + ext;
    }

    @Override
    public String postUseParams(String id) {
        JSONObject jsonObject = JSON.parseObject(id);
        return PREFIX + jsonObject.getString("id");
    }

    @Override
    public String getHead(String id, String myHeader) {
        return PREFIX + id + " header:" + myHeader;
    }

    @Override
    public String patchById(@PathVariable String id, @RequestBody String patchData) {
        JSONObject jsonObject = JSON.parseObject(patchData);
        String name = jsonObject.getString("name");

        return PREFIX + id + " " + name;
    }

    @Override
    public String postUseConsumesUser(@RequestBody MultiValueMap<String, List<User>> formData) {
        return PREFIX + formData.get("user1").get(0).get(0).getName();
    }

    @Override
    public List<User> postList(List<User> users) {
        return users;
    }
}
