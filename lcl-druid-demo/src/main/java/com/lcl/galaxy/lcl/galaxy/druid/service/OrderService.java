package com.lcl.galaxy.lcl.galaxy.druid.service;

import com.lcl.galaxy.lcl.galaxy.druid.dao.OrderNewMapper;
import com.lcl.galaxy.lcl.galaxy.druid.domain.OrderInfoNew;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
@Slf4j
public class OrderService {

    @Autowired
    private OrderNewMapper orderNewMapper;
    @Autowired
    private RestTemplate restTemplate;

    public void save(OrderInfoNew orderInfo) {
        orderNewMapper.insert(orderInfo);
    }

    public List<OrderInfoNew> query(OrderInfoNew build) {
//        ResponseEntity<String> forEntity = restTemplate.getForEntity("https://baidu.com", String.class);
        List<OrderInfoNew> orderInfoNewList = orderNewMapper.findAll();
        log.info("查询结果为==={}",orderInfoNewList);
        return orderInfoNewList;
    }

    public void deleteByVenderId(int venderId) {
        orderNewMapper.deleteByVenderId(venderId);
    }
}
