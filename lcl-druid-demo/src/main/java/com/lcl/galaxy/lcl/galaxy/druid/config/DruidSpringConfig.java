package com.lcl.galaxy.lcl.galaxy.druid.config;

import com.alibaba.druid.support.spring.stat.DruidStatInterceptor;
import org.springframework.aop.support.DefaultBeanFactoryPointcutAdvisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.JdkRegexpMethodPointcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DruidSpringConfig {


    /**
     * 创建Druid连接器
     * @return
     */
    @Bean("druidStatInterceptor")
    public DruidStatInterceptor getDruidStatInterceptor(){
        DruidStatInterceptor druidStatInterceptor = new DruidStatInterceptor();
        return druidStatInterceptor;
    }

    /**
     * 获取切面
     * @return
     */
    @Bean("jdkRegexpMethodPointcut")
    public JdkRegexpMethodPointcut getJdkRegexpMethodPointcut(){
        JdkRegexpMethodPointcut jdkRegexpMethodPointcut = new JdkRegexpMethodPointcut();
        jdkRegexpMethodPointcut.setPatterns("com.lcl.galaxy.lcl.galaxy.druid.dao.*","com.lcl.galaxy.lcl.galaxy.druid.apis.*","com.lcl.galaxy.lcl.galaxy.druid.service.*");
        //jdkRegexpMethodPointcut.setPattern("com.lcl.galaxy.lcl.galaxy.druid..*");
        return jdkRegexpMethodPointcut;
    }

    /**
     * 使用AOP模式实现切面
     * @param druidStatInterceptor
     * @param pointcut
     * @return
     */
    @Bean("druidSpringStatAdvisor")
    public DefaultPointcutAdvisor getDruidSpringStatAdvisor(DruidStatInterceptor druidStatInterceptor, JdkRegexpMethodPointcut pointcut){
        DefaultPointcutAdvisor defaultPointcutAdvisor = new DefaultPointcutAdvisor();
        defaultPointcutAdvisor.setPointcut(pointcut);
        defaultPointcutAdvisor.setAdvice(druidStatInterceptor);
        return defaultPointcutAdvisor;
    }
}
