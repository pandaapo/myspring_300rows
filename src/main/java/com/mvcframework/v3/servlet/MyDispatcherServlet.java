package com.mvcframework.v3.servlet;

import com.mvcframework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyDispatcherServlet extends HttpServlet {
    //保存application.properties配置文件中的内容
    private Properties contextConfig = new Properties();
    //保存扫描到的类名
    private List<String> classNames = new ArrayList<String>();
    //IOC容器
    private Map<String, Object> ioc = new HashMap<String, Object>();
    //保存url和method的对应关系
//    private Map<String, Method> handlerMapping = new HashMap<String, Method>();
    private List<HandlerMapping> handlerMappings = new ArrayList<HandlerMapping>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
       doPost(req,resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //6 调用。
        try {
            doDispatch(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Exception, detail=" + Arrays.toString(e.getStackTrace()));
        }
    }

    // 运行阶段
    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        HandlerMapping handlerMapping = getHandler(req);
        if(handlerMapping == null){
            resp.getWriter().write("404 Not Found!");
            return;
        }

        //方法的形参列表
        Class<?>[] paramTypes = handlerMapping.getParamTypes();
        //申明参数值数组
        Object[] paramValues = new Object[paramTypes.length];
        //方法中的参数名称和参数值Map
        Map<String, String[]> params = req.getParameterMap();
        for (Map.Entry<String, String[]> param : params.entrySet()) {
            //将数组处理成逗号分隔的字符串
            String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]","").replaceAll("\\s",",");
            if(!handlerMapping.paramIndexMapping.containsKey(param.getKey()))
                continue;
            int index = handlerMapping.paramIndexMapping.get(param.getKey());
            paramValues[index] = convert(paramTypes[index], value);
        }

        if(handlerMapping.paramIndexMapping.containsKey(HttpServletRequest.class.getName())){
            int reqIndex = handlerMapping.paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[reqIndex] = req;
        }

        if(handlerMapping.paramIndexMapping.containsKey(HttpServletResponse.class.getName())){
            int respIndex = handlerMapping.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[respIndex] = resp;
        }

        Object returnValue = handlerMapping.method.invoke(handlerMapping.controller, paramValues);
        if(returnValue == null || returnValue instanceof Void) {
            return;
        }
        resp.getWriter().write(returnValue.toString());
    }

    private HandlerMapping getHandler(HttpServletRequest req) {
        if(handlerMappings.isEmpty()){
            return null;
        }
        //绝对路径
        String url = req.getRequestURI();
        //处理成相对路径
        String  contextPath = req.getContextPath();
        url = url.replaceAll(contextPath, "").replaceAll("/+", "/");
        for (HandlerMapping h: handlerMappings) {
            Matcher matcher = h.getUrl().matcher(url);
            if(!matcher.matches()) continue;
            return h;
        }
        return null;
    }

    //url传的参数都是string类型，因为http是基于字符串的协议
    //把String转换成Integer
    private Object convert(Class<?> type, String value){
        if(Integer.class == type) {
            return Integer.valueOf(value);
        } else if (Double.class == type){
            return Double.valueOf(value);
        }
        //其他类型转换
        //考虑使用策略模式优化很多的if else
        return value;
    }

    //初始化阶段
    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        //1、加载配置文件
        doLoadConfig(servletConfig.getInitParameter("contextConfigLocation"));
        //2、扫描相关的类
        doScanner(contextConfig.getProperty("scanPackage"));
        //3、初始化扫描到的类，并放入到IOC容器中
        doInstance();
        //4、完成依赖注入
        doAutowired();
        //5、初始化HandlerMapping
        initHandlerMapping();
        System.out.println("My Spring framework is init.");
    }

    //初始化url和Method的一对一对应关系
    private void initHandlerMapping() {
        if(ioc.isEmpty()) return;
        for (Map.Entry<String,Object> entry: ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if(!clazz.isAnnotationPresent(MyController.class)) continue;
            //保存写在类上面的@MyRequestMapping("/demo")
            String baseUrl = "";
            if(clazz.isAnnotationPresent(MyRequestMapping.class)){
                MyRequestMapping requestMapping = clazz.getAnnotation(MyRequestMapping.class);
                baseUrl = requestMapping.value();
            }
            //默认获取所有的public方法
            for (Method method: clazz.getMethods()) {
                if(!method.isAnnotationPresent(MyRequestMapping.class)) continue;
                MyRequestMapping requestMapping = method.getAnnotation(MyRequestMapping.class);
                String url = (baseUrl + "/" + requestMapping.value()).replaceAll("/+","/");
                Pattern pattern = Pattern.compile(url);
//                handlerMapping.put(url, method);
                handlerMappings.add(new HandlerMapping(pattern, method, entry.getValue()));
                System.out.println("Mapped: " + url + "," + method);
            }
        }

    }

    //自动依赖注入
    private void doAutowired() {
        if(ioc.isEmpty()) return;
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            //获取所有声明的属性，包括private protected
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field: fields) {
                if(!field.isAnnotationPresent(MyAutowired.class)) continue;
                MyAutowired autowired = field.getAnnotation(MyAutowired.class);
                String beanName = autowired.value().trim();
                if("".equals(beanName)){
                    //如果没有自定义注解名称，则获取类型名称，采用类型注入
                    beanName = field.getType().getName();
                }
                // 如果是public以外的修饰符，要取消JDK的检查，来强制赋值
                field.setAccessible(true);
                try {
                    //用反射机制，给entry.value()这个对象的这个属性（field）赋值（ioc.get(beanName)）
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void doInstance() {
        //初始化时，为DI提前准备
        if(classNames.isEmpty()) return;
        try {
            for(String className: classNames){
                Class<?> clazz = Class.forName(className);
                //初始化这些类：加了注解的
                if(clazz.isAnnotationPresent(MyController.class)){
                    Object instance = clazz.newInstance();
                    //ioc中的key值spring默认采用类名首字母小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName, instance);
                } else if (clazz.isAnnotationPresent(MyService.class)) {
                    //1、自定的beanName
                    MyService service = clazz.getAnnotation(MyService.class);
                    String beanName = service.value();
                    //2、默认类名首字母小写
                    beanName = toLowerFirstCase(clazz.getSimpleName());
                    Object instance = clazz.newInstance();
                    ioc.put(beanName, instance);
                    //3、根据类型自动注入。比如接口，本身是不能实例化的
                    for(Class<?> i: clazz.getInterfaces()){
                        if(ioc.containsKey(i.getName())){
                            throw new Exception("The “" +i.getName()+ "” exists.");
                        }
                        ioc.put(i.getName(), instance);
                    }
                } else {
                    continue;
                }
            }
        } catch (Exception e){
            e.printStackTrace();
        }

    }

    //将字符串的首字母转成小写
    private String toLowerFirstCase(String simpleName){
        char[] chars = simpleName.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

    //扫描出相关的类
    private void doScanner(String scanPackage) {
        //将包路径转换成文件路径
        URL url =this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
        File classPath = new File(url.getFile());
        for(File file: classPath.listFiles()){
            if(file.isDirectory()){
                doScanner(scanPackage + "." + file.getName());
            } else {
                if(file.getName().endsWith(".class")){
                    String className = (scanPackage + "." + file.getName().replace(".class",""));
                    classNames.add(className);
                }
            }
        }
    }

    //将配置文件加载到内存中。
    private void doLoadConfig(String contextConfigLocation) {
        //从类路径下找到spring主配置文件所在路径。并将其读取出来放到Properties对象中，相当于将scanPackage=com.mvc加载到内存中
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(null != is){
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    //初始化就能获得这些信息
    // 保存一个url和一个Method的关系
    private class HandlerMapping{
        //用Pattern不用String，可以让请求支持正则
        private Pattern url;
        private Method method;
        private Object controller;
        //形参集合：参数类型数组
        private Class<?>[] paramTypes;
        //形参名称和位置的Map：参数的名字作为key，参数的在方法中位置作为value
        private Map<String, Integer> paramIndexMapping;

        public Pattern getUrl() {
            return url;
        }

        public Method getMethod() {
            return method;
        }

        public Object getController() {
            return controller;
        }

        public Class<?>[] getParamTypes() {
            return paramTypes;
        }

        public HandlerMapping(Pattern url, Method method, Object controller) {
            this.url = url;
            this.method = method;
            this.controller = controller;

            paramIndexMapping = new HashMap<String, Integer>();
            putParamIndexMapping(method);
            paramTypes = method.getParameterTypes();
        }

        private void putParamIndexMapping(Method method) {
            //提取方法中加了注解的参数，得到的是二维数组。因为一个方法有多个参数，一个参数可以有多个注解
            Annotation[][] pa = method.getParameterAnnotations();
            for (int i = 0; i < pa.length; i++) {
                for (Annotation a: pa[i]) {
                    if(a instanceof  MyRequestParam){
                        String paramName = ((MyRequestParam) a).value();
                        if(!"".equals(paramName.trim())){
                            //a: 0, b:1, javax.servlet.http.HttpServletRequest: 2, javax.servlet.http.HttpServletResponse: 3
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }

            //提取方法中的request和response
            Class<?>[] parasTypes = method.getParameterTypes();
            for (int i = 0; i < parasTypes.length; i++) {
                Class<?> type = parasTypes[i];
                if(type == HttpServletRequest.class || type == HttpServletResponse.class){
                    paramIndexMapping.put(type.getName(), i);
                }
            }
        }

    }
}
