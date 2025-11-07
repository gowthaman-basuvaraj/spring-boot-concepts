package com.example.demo.dto;


import com.example.demo.check.ContactNumberConstraint;
import com.example.demo.check.FieldsValueMatch;

@FieldsValueMatch.List({
        @FieldsValueMatch(
                field = "email",
                fieldMatch = "verifyEmail",
                message = "Email addresses do not match!"
        )
})

public class UserDto {
    public String name;
    public String userName;
    public Long id;
    @ContactNumberConstraint
    public String contactNumber;
    public String email;
    public String verifyEmail;
}
