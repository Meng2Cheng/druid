package com.lcl.galaxy.lcl.galaxy.druid.apis;

import com.lcl.galaxy.lcl.galaxy.druid.domain.OrderInfoNew;
import com.lcl.galaxy.lcl.galaxy.druid.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/order")
public class OrderApi {

    @Autowired
    private OrderService orderService;

    @RequestMapping("/save")
    public String save(long orderId, long venderId){
        orderService.save(OrderInfoNew.builder().orderId(orderId).venderId(venderId).build());
        return "OK";
    }


    @RequestMapping("/saveNew")
    public String saveNew(long orderId, long venderId){
        orderService.save(OrderInfoNew.builder().orderId(orderId).venderId(venderId).build());
        return "OK";
    }

    @RequestMapping("/delete")
    public String save(int venderId){
        orderService.deleteByVenderId(venderId);
        return "OK";
    }


    @RequestMapping("/query")
    public String query(long orderId, long venderId){
        return orderService.query(OrderInfoNew.builder().orderId(orderId).venderId(venderId).build()).toString();
    }

}
