package com.example.demo.service;

import com.example.demo.dao.UserRepo;
import com.example.demo.entity.User;
import com.example.demo.utils.DBConfig;
import com.example.demo.utils.RoleConfig;
import com.example.demo.utils.UserExistsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {
    private final UserRepo userRepo;
    private final RoleConfig defaultConfig;
    private final RoleConfig adminRoleConfig;
    private final DBConfig dbConfig;

    @Autowired
    public UserService(UserRepo userRepo,
                       DBConfig dbConfig,
                       @Qualifier("defaultRoleConfig") RoleConfig defaultRoleConfig,
                       @Qualifier("adminRoleConfig") RoleConfig adminRoleConfig
    ) {
        this.dbConfig = dbConfig;
        this.userRepo = userRepo;
        this.defaultConfig = defaultRoleConfig;
        this.adminRoleConfig = adminRoleConfig;
    }

    public void saveUser(User user) throws UserExistsException {
        System.out.println(defaultConfig.allowed());
        System.out.println(adminRoleConfig.allowed());
        System.out.println("jsonb allowed? " + dbConfig.jsonB());
        if (userRepo.findByUserName(user.getUserName()).isEmpty()) {
            userRepo.save(user);
        } else {
            throw new UserExistsException(String.format("User %s already exists", user.getUserName()));
        }
    }

    public void deleteUser(Long id) {
        userRepo.deleteById(id);
    }

    public Optional<User> getUser(Long id) {
        return userRepo.findById(id);
    }

    public Iterable<User> getAllUsers() {
        return userRepo.findAll();
    }

    public List<User> searchByPinCode(String s) {
        return userRepo.findByPinCode(s);
    }

    public List<User> searchByName(String s) {
        return userRepo.searchByName(s);
    }
}
