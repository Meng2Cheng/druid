package com.lcl.galaxy.lcl.galaxy.mysql;

import com.lcl.galaxy.lcl.galaxy.druid.LclGalaxyDruidApplication;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;

import javax.sql.DataSource;

@ExtendWith(SpringExtension.class) // 使用 Junit5
@WebAppConfiguration // 启用 Web 环境
@SpringBootTest(classes = LclGalaxyDruidApplication.class)
@Slf4j
public class DruidTest {

    @Autowired
    DataSource dataSource;

    @Test
    public void testBasic(){
        try {
            log.info("" + this.dataSource.getConnection());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
