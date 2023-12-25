package com.lcl.galaxy.lcl.galaxy.druid.aop;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.message.Message;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@Aspect
public class LimiteAop {
    private final Map<String, RateLimiter> limiterMap = new ConcurrentHashMap<>();



    @Around(value = "execution(* com.lcl.galaxy.lcl.galaxy.druid.apis..*.*(..)) &&@annotation(com.lcl.galaxy.lcl.galaxy.druid.aop.GuavaLimiter)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method method = methodSignature.getMethod();
        GuavaLimiter guavaLimiter = method.getAnnotation(GuavaLimiter.class);
        if(guavaLimiter != null){
            String key = guavaLimiter.key();
            RateLimiter rateLimiter = limiterMap.computeIfAbsent(key,
                    k -> RateLimiter.create(guavaLimiter.permitsPerSecond(), guavaLimiter.timeOut(), guavaLimiter.timeUnit()));
            boolean acquire = rateLimiter.tryAcquire(guavaLimiter.timeOut(), guavaLimiter.timeUnit());
            if(!acquire){
                log.error(guavaLimiter.key() + "=====" + guavaLimiter.msg());
                return "9999:" + guavaLimiter.msg();
            }
        }
        return joinPoint.proceed();
    }
}
