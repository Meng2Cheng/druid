package com.lcl.galaxy.lcl.galaxy.druid.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lcl.galaxy.lcl.galaxy.druid.domain.OrderInfoNew;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
@Component
public interface OrderNewMapper extends BaseMapper<OrderInfoNew> {

    List<OrderInfoNew> findAll();

    void deleteByVenderId(int venderId);
}
