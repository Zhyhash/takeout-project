package org.example.takeout.Merchant.DTO;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;

@Data
// NOTE: 商家修改自己的店铺名称、头像、简介和营业时间
public class MerchantUpdateDTO {
    @Size(max = 255, message = "商家名称长度不能超过255个字符")
    private String merchantName;

    @Size(max = 255, message = "地址长度不能超过255个字符")
    private String address;

    @Size(max = 20, message = "手机号码长度不能超过20个字符")
    @Pattern(regexp = "^(?:(?:\\+|00)86)?1[3-9]\\d{9}$", message = "手机格式不正确，请重新输入")
    private String phone;

    @Size(max = 255, message = "店铺简介长度不能超过255个字符")
    private String description;

    @Size(max = 255, message = "图片URL长度不能超过255个字符")
    private String pictureURL;

    private LocalTime openingTime;
    private LocalTime closingTime;

    @JsonIgnore
    @AssertTrue(message = "商家名称不能为空白")
    public boolean isMerchantNameValid() {
        return merchantName == null || !merchantName.isBlank();
    }

    @JsonIgnore
    @AssertTrue(message = "地址不能为空白")
    public boolean isAddressValid() {
        return address == null || !address.isBlank();
    }

    @JsonIgnore
    @AssertTrue(message = "手机号码不能为空白")
    public boolean isPhoneValid() {
        return phone == null || !phone.isBlank();
    }

    @JsonIgnore
    @AssertTrue(message = "至少提供一个需要更新的字段")
    public boolean isAnyFieldPresent() {
        return merchantName != null
                || address != null
                || phone != null
                || description != null
                || pictureURL != null
                || openingTime != null
                || closingTime != null;
    }
}
