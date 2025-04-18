package org.apache.dubbo.demo.provider;

import org.apache.dubbo.demo.GreetingService;
import org.springframework.stereotype.Service;

@Service
public class GreetingServiceImpl implements GreetingService {
    @Override
    public String hello() {
        return "hello spring bean";
    }
}
