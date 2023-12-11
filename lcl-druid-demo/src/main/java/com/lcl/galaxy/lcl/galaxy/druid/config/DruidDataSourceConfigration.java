package com.lcl.galaxy.lcl.galaxy.druid.config;

import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.filter.logging.LogFilter;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class DruidDataSourceConfigration {

    @Bean("dataSource")
    public DataSource getDatasource(
            @Value("${spring.lcl.datasource.druid.driver-class-name}")
            String driverClassName,
            @Value("${spring.lcl.datasource.druid.url}")
            String url,
            @Value("${spring.lcl.datasource.druid.username}")
            String userName,
            @Value("${spring.lcl.datasource.druid.password}")
            String password,
            @Value("${spring.lcl.datasource.druid.initial-size}")
            int initialSize,
            @Value("${spring.lcl.datasource.druid.min-idle}")
            int minIdle,
            @Value("${spring.lcl.datasource.druid.max-active}")
            int maxActive,
            @Value("${spring.lcl.datasource.druid.max-wait}")
            int maxWait,
            @Value("${spring.lcl.datasource.druid.time-between-eviction-runs-millis}")
            int timeBetweenEvictionRunsMillis,
            @Value("${spring.lcl.datasource.druid.min-evictable-idle-time-millis}")
            int minEvictableIdleTimeMillis,
            @Value("${spring.lcl.datasource.druid.validation-query}")
            String validationQuery,
            @Value("${spring.lcl.datasource.druid.validation-query-timeout}")
            int validationQueryTimeout,
            @Value("${spring.lcl.datasource.druid.test-while-idle}")
            boolean testWhileIdle,
            @Value("${spring.lcl.datasource.druid.test-on-borrow}")
            boolean testOnBorrow,
            @Value("${spring.lcl.datasource.druid.test-on-return}")
            boolean testOnReturn,
            @Value("${spring.lcl.datasource.druid.pool-prepared-statements}")
            boolean poolPreparedStatements,
            @Value("${spring.lcl.datasource.druid.max-pool-prepared-statement-per-connection-size}")
            int maxPoolPreparedStatementPerConnectionSize,
            @Autowired StatFilter sqlStatFilter,
            @Autowired WallFilter wallFilter,
            @Autowired LogFilter logFilter
            ){
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setUrl(url);
        dataSource.setUsername(userName);
        dataSource.setPassword(password);
        dataSource.setInitialSize(initialSize);
        dataSource.setMinIdle(minIdle);
        dataSource.setMaxActive(maxActive);
        dataSource.setMaxWait(maxWait);
        dataSource.setTimeBetweenEvictionRunsMillis(timeBetweenEvictionRunsMillis);
        dataSource.setMinEvictableIdleTimeMillis(minEvictableIdleTimeMillis);
        dataSource.setValidationQuery(validationQuery);
        dataSource.setValidationQueryTimeout(validationQueryTimeout);
        dataSource.setTestWhileIdle(testWhileIdle);
        dataSource.setTestOnBorrow(testOnBorrow);
        dataSource.setTestOnReturn(testOnReturn);
        dataSource.setPoolPreparedStatements(poolPreparedStatements);
        dataSource.setMaxPoolPreparedStatementPerConnectionSize(maxPoolPreparedStatementPerConnectionSize);

        List<Filter> filterList = new ArrayList<>();
        filterList.add(sqlStatFilter);
        filterList.add(wallFilter);
        filterList.add(logFilter);
        dataSource.setProxyFilters(filterList);
        return dataSource;
    }
}
