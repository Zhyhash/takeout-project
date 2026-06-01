package org.example.tokeout.User.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.tokeout.User.Entity.User;


@Mapper
public interface UserMapper extends BaseMapper<User> {

}
