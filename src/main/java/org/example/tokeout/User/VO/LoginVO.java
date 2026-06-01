package org.example.tokeout.User.VO;

import lombok.Data;

@Data
public class LoginVO {
    private String token;
    private Long id;
    private String nickname;
}
