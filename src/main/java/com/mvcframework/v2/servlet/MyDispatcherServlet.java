package com.mvcframework.v2.servlet;

import com.mvcframework.annotation.*;

import javax.servlet.Servlet;
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

public class MyDispatcherServlet extends HttpServlet {
    //保存application.properties配置文件中的内容
    private Properties contextConfig = new Properties();
    //保存扫描到的类名
    private List<String> classNames = new ArrayList<String>();
    //IOC容器
    private Map<String, Object> ioc = new HashMap<String, Object>();
    //保存url和method的对应关系
    private Map<String, Method> handlerMapping = new HashMap<String, Method>();

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
        //绝对路径
        String url = req.getRequestURI();
        //处理成相对路径
        String contextPath = req.getContextPath();
        url = url.replaceAll(contextPath, "").replaceAll("/+","/");
        if(!this.handlerMapping.containsKey(url)){
            resp.getWriter().write("404 not found!");
            return;
        }

        Method method = this.handlerMapping.get(url);

        //获取方法的形参列表
        Class<?> [] parameterTypes = method.getParameterTypes();
        //保存请求的url参数列表
        Map<String, String[]> paramterMap = req.getParameterMap();
        //保存赋值参数的位置
        Object[] paramValues = new Object[parameterTypes.length];
        //根据参数位置动态赋值。形参和实参对应起来。
        for (int i=0; i < parameterTypes.length; i++){
             Class parameterType = parameterTypes[i];
             if(parameterType == HttpServletRequest.class){
                 paramValues[i] = req;
                 continue;
             } else if(parameterType == HttpServletResponse.class){
                 paramValues[i] = resp;
                 continue;
             }
//                 MyRequestParam requestParam = (MyRequestParam) parameterType.getAnnotation(MyRequestParam.class);
//                 if(paramterMap.containsKey(requestParam.value())){
//                     for(Map.Entry<String, String[]> param: paramterMap.entrySet()){
//                         String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]","").replaceAll("\\s",",");
//                         paramValues[i] = value;
//                     }
//                 }
             //获取方法上有注解的参数名称
             Annotation[][] annotations = method.getParameterAnnotations();
             for (int j = 0; j <annotations.length ; j++) {
                 for (Annotation a : annotations[j]) {
                     if(a instanceof MyRequestParam){
                         //参数名称
                         String paramName = ((MyRequestParam) a).value();
                         //与url中的参数进行匹配
                         if(paramterMap.containsKey(paramName)){
                             //url中的参数值，一个参数名可以有多个参数值，所以paramterMap.get(paramName)是数组
                             //这里将参数处理为String类型是为了简化写死的，实际可能是其他类型。
                             String value = Arrays.toString(paramterMap.get(paramName))
                                     .replaceAll("\\[|\\]", "")
                                     .replaceAll("\\s", ",");

                             //类型强制转换
                             paramValues[i] = convert(parameterType, value);
                         }
                     }
                 }
             }
        }

        //通过反射拿到method所在的class，拿到class之后可以拿到class的名称，最后可以获得beanName
        String beanName = toLowerFirstCase(method.getDeclaringClass().getSimpleName());
        //第一个参数：方法所在的实例，第二个参数：调用时所需要的实参
        method.invoke(ioc.get(beanName), paramValues);
    }

    //url传的参数都是string类型，因为http是基于字符串的协议
    //把String转换成Integer
    private Object convert(Class<?> type, String value){
        if(Integer.class == type) {
            return Integer.valueOf(value);
        }
        //double类型转换
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
                handlerMapping.put(url, method);
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
}
