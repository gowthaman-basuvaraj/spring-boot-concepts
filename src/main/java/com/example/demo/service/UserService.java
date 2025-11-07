package com.example.demo.service;

import com.example.demo.dao.UserRepo;
import com.example.demo.entity.User;
import com.example.demo.utils.DBConfig;
import com.example.demo.utils.RoleConfig;
import com.example.demo.utils.UserExistsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class UserService {
    @Autowired
    UserRepo userRepo;

    @Autowired
    @Qualifier("defaultRoleConfig")
    RoleConfig defaultConfig;

    @Autowired
    @Qualifier("adminRoleConfig")
    RoleConfig adminRoleConfig;

    @Autowired
    DBConfig dbConfig;

    @Qualifier("withAuth")
    @Autowired
    WebClient webClient;

    @Autowired
    WebClient advancedWebClient;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    CircuitBreakerFactory cbFactory;


    public void saveUser(User user) throws UserExistsException {
        System.out.println(defaultConfig.allowed());
        System.out.println(adminRoleConfig.allowed());
        System.out.println("jsonb allowed? " + dbConfig.jsonB());
        if (userRepo.findByUserName(user.getUserName()).isEmpty()) {
            userRepo.save(user);
            cbFactory.create("user").run(new Supplier<Object>() {
                @Override
                public Object get() {
                    return "OK";
                }
            }, new Function<Throwable, Object>() {

                @Override
                public Object apply(Throwable throwable) {
                    return "FAIL";
                }
            });
            eventPublisher.publishEvent(new UserRegisteredEvent(user.getUserName() + "@gmail.com"));
        } else {
            throw new UserExistsException(String.format("User %s already exists", user.getUserName()));
        }
    }

    public void deleteUser(Long id) {
        userRepo.deleteById(id);
    }

    public Optional<User> getUser(Long id) {
        webClient.get()
                .uri("/api/users/{id}", id)
                .retrieve()
                .bodyToMono(User.class)
                .block();
        return userRepo.findById(id);
    }

    // Single query with JOIN
    public List<User> getAllUsersWithPosts() {
        return userRepo.findAllWithPosts();
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
