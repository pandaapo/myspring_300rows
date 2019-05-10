package com.mvcframework.annotation;

import java.lang.annotation.*;

/**直接抄了spring源码的RequestMapping类，并简化*/
@Target({ElementType.TYPE,ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MyRequestMapping {
    String value() default "";
}
