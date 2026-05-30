package com.example.orchestrator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orchestrator.model.entity.TaskType;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskTypeMapper extends BaseMapper<TaskType> {
}
