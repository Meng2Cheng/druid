package com.lcl.galaxy.lcl.galaxy.druid.aop;


import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
@Documented
public @interface GuavaLimiter {

    // 资源key，设置为接口名称
    String key() default "";

    // 每秒限流
    double permitsPerSecond();

    // 获取令牌超时时间
    long timeOut() default 200;

    // 获取令牌超时时间单位
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;

    // 获取令牌失败错误提示
    String msg() default "请求过于频繁，已被限流，请稍后重试";

}
