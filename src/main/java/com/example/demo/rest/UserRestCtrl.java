package com.example.demo.rest;

import com.example.demo.dto.UserDto;
import com.example.demo.entity.User;
import com.example.demo.service.UserService;
import com.example.demo.utils.UserExistsException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api")
public class UserRestCtrl {
    private final UserService userService;

    @Autowired
    public UserRestCtrl(UserService service) {
        this.userService = service;
    }

    @GetMapping(value = "/user/{id}", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public User getUser(@PathVariable("id") Long id) {
        return userService.getUser(id).orElseThrow();
    }

    @PostMapping(value = "/user", consumes = {MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> saveUser(@Valid UserDto user) throws UserExistsException {

        var newUser = new User();
        newUser.setName(user.name);
        newUser.setUserName(user.userName);
        userService.saveUser(newUser);

        return ResponseEntity.accepted().build();
    }

    @GetMapping("/users")
    public List<User> getUsers(
            @RequestParam(required = false, name = "pinCode") Optional<String> pinCode,
            @RequestParam(required = false, name = "name") Optional<String> name
    ) {
        if (pinCode.isPresent()) {
            return userService.searchByPinCode(pinCode.get());
        } else if (name.isPresent()) {
            return userService.searchByName(name.get());
        } else {
            return StreamSupport
                    .stream(userService.getAllUsers().spliterator(), false)
                    .toList();
        }
    }
}
