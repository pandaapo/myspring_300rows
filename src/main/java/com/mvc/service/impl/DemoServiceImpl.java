package com.mvc.service.impl;

import com.mvc.service.IDemoService;

public class DemoServiceImpl implements IDemoService {
    @Override
    public String get(String name) {
        return "My name is " + name;
    }
}
