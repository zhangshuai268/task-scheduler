package com.example.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orchestrator.model.entity.Task;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {
}
