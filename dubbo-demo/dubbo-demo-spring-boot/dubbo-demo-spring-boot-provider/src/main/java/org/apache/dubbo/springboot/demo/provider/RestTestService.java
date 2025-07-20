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

import org.apache.dubbo.springboot.demo.User;

import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RequestMapping("/demo")
public interface RestTestService {

    @GetMapping(value = "get/muchParam")
    String getMuchParam(@RequestParam String id, @RequestParam String name);

    @GetMapping(value = "get/muchVariable/{id}/{name}")
    String getMuchVariable(@PathVariable String id, @PathVariable String name);

    @GetMapping(value = "get/reg/{name:[a-z-]+}-{version:\\d\\.\\d\\.\\d}{ext:\\.[a-z]+}")
    String getReg(@PathVariable String name, @PathVariable String version, @PathVariable String ext);

    @PostMapping(value = "post/useParams", params = "myParam=myValue")
    String postUseParams(@RequestBody String id);

    @GetMapping(value = "get/head/{id}", headers = "myHeader=myValue")
    String getHead(@PathVariable String id, @RequestHeader String myHeader);

    @PostMapping(value = "/post/list", consumes = MediaType.ALL_VALUE)
    List<User> postList(@RequestBody List<User> users);

    @PatchMapping("patch/{id}")
    String patchById(@PathVariable String id, @RequestBody String patchData);

    @PostMapping(value = "post/useConsumes/user", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    String postUseConsumesUser(@RequestBody MultiValueMap<String, List<User>> formData);

}
