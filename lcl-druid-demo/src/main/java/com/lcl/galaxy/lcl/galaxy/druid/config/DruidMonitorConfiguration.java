package com.lcl.galaxy.lcl.galaxy.druid.config;

import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.support.http.StatViewServlet;
import com.alibaba.druid.support.http.WebStatFilter;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration
public class DruidMonitorConfiguration {

    /**
     * 开启监控
     * @return
     */
    @Bean("druidStatViewServlet")
    public ServletRegistrationBean<StatViewServlet> getDruidStatViewServlet(
            @Value("${spring.lcl.datasource.druid.stat-view-servlet.login-username}")
            String username,
            @Value("${spring.lcl.datasource.druid.stat-view-servlet.login-password}")
            String password,
            @Value("${spring.lcl.datasource.druid.stat-view-servlet.enabled}")
            String resetEnable
    ){
        ServletRegistrationBean<StatViewServlet> registrationBean = new ServletRegistrationBean<>(new StatViewServlet(), "/druid/*");
        registrationBean.addInitParameter(StatViewServlet.PARAM_NAME_ALLOW, "127.0.0.1"); // 白名单
        registrationBean.addInitParameter(StatViewServlet.PARAM_NAME_DENY, ""); // 黑名单
        registrationBean.addInitParameter(StatViewServlet.PARAM_NAME_USERNAME, username); // 用户名
        registrationBean.addInitParameter(StatViewServlet.PARAM_NAME_PASSWORD, password); // 密码
        registrationBean.addInitParameter(StatViewServlet.PARAM_NAME_RESET_ENABLE, resetEnable); // 允许重置
        return  registrationBean;
    }


    /**
     * url 监控
     * @param webStatFilter
     * @return
     */
    @Bean
    @DependsOn("webStatFilter")
    public FilterRegistrationBean<WebStatFilter> getDruidWebStatFilter(
            WebStatFilter webStatFilter,
            @Value("${spring.lcl.datasource.druid.web-stat-filter.url-pattern}")
            String UrlPatterns,
            @Value("${spring.lcl.datasource.druid.web-stat-filter.exclusions}")
            String exclusions){
        FilterRegistrationBean<WebStatFilter> registrationBean = new FilterRegistrationBean<>(webStatFilter);
        registrationBean.addUrlPatterns("/*"); // 对所有的路径都进行监控配置
        registrationBean.addInitParameter(WebStatFilter.PARAM_NAME_EXCLUSIONS, "*.js,*.gif,*.jpg,*.bmp,*.css,*.ico,/druid/*"); // 路径排除
        return registrationBean;
    }

    @Bean("webStatFilter")
    public WebStatFilter getWebStatFilter(
            @Value("${spring.lcl.datasource.druid.web-stat-filter.enabled}")
            boolean enabled
    ){
        // 获取 web 状态过滤
        WebStatFilter webStatFilter = new WebStatFilter();
        // 对 session 状态进行过滤
        webStatFilter.setSessionStatEnable(enabled);
        return webStatFilter;
    }


    /**
     * 定义sql监控
     * @return
     */
    @Bean("sqlStatFilter")
    public StatFilter getSqlStatFilter(
            @Value("${spring.lcl.datasource.druid.filter.stat.merge-sql}")
            boolean mergeSql,
            @Value("${spring.lcl.datasource.druid.filter.stat.log-slow-sql}")
            boolean logSlowSql,
            @Value("${spring.lcl.datasource.druid.filter.stat.slow-sql-millis}")
            int slowSqlMillis
    ){
        StatFilter statFilter = new StatFilter();
        statFilter.setMergeSql(mergeSql); // 是否要合并统计
        statFilter.setLogSlowSql(logSlowSql); // 慢sql统计
        statFilter.setSlowSqlMillis(slowSqlMillis); // 慢sql执行时间（毫秒）
        return statFilter;
    }

    @Bean("wallConfig")
    public WallConfig getWallConfig(
            @Value("${spring.lcl.datasource.druid.filter.wall.config.multi-statement-allow}")
            boolean multiStatementAllow,
            @Value("${spring.lcl.datasource.druid.filter.wall.config.delete-allow}")
            boolean deleteAllow
    ){
        WallConfig wallConfig = new WallConfig();
        wallConfig.setMultiStatementAllow(multiStatementAllow); // 是否允许并行多个statement操作（批处理）
        wallConfig.setDeleteAllow(deleteAllow); // 是否允许执行删除
        return wallConfig;
    }

    @Bean("wallfFilter")
    public WallFilter getWallFilter(WallConfig wallConfig){
        WallFilter wallFilter = new WallFilter();
        wallFilter.setConfig(wallConfig);
        return wallFilter;
    }
}
