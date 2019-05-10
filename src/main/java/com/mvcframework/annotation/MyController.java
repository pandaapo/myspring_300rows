package com.mvcframework.annotation;

import java.lang.annotation.*;

/**直接抄了spring源码的Controller类*/
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MyController {
    String value() default "";
}
