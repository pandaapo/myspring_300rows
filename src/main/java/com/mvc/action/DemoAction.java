package com.mvc.action;

import com.mvc.service.IDemoService;
import com.mvcframework.annotation.MyAutowired;
import com.mvcframework.annotation.MyController;
import com.mvcframework.annotation.MyRequestMapping;
import com.mvcframework.annotation.MyRequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

//形式上的简单实现springmvc，但没有功能
@MyController
@MyRequestMapping("/demo")
public class DemoAction {
    @MyAutowired
    private IDemoService demoService;

    @MyRequestMapping("/query")
    public void query(HttpServletRequest req, HttpServletResponse resp, @MyRequestParam("name") String name){
//        String result = demoService.get(name);
        String result2 = "My name is " + name;
        try {
            resp.getWriter().write(result2);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @MyRequestMapping("/add")
    public void add(HttpServletRequest req, HttpServletResponse resp, @MyRequestParam("a") Integer a, @MyRequestParam("b") Integer b){
        try {
            resp.getWriter().write(a + "+" + b + "=" + (a+b));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @MyRequestMapping("/remove")
    public String remove(HttpServletRequest req, HttpServletResponse resp, @MyRequestParam("id") Integer id){
        return "" + id;
    }
}
