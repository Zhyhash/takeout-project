package org.example.takeout.User.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.takeout.User.Entity.User;


@Mapper
public interface UserMapper extends BaseMapper<User> {

}
