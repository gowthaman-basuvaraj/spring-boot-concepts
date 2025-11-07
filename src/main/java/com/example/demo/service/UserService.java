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
    private final UserRepo userRepo;
    private final RoleConfig defaultConfig;
    private final RoleConfig adminRoleConfig;
    private final DBConfig dbConfig;
    private final WebClient webClient;
    private final WebClient advancedWebClient;
    // 1. Inject the publisher
    private final ApplicationEventPublisher eventPublisher;
    private CircuitBreakerFactory cbFactory;


    @Autowired
    public UserService(UserRepo userRepo,
                       DBConfig dbConfig,
                       @Qualifier("withAuth") WebClient webClient,
                       WebClient advancedWebClient,
                       @Qualifier("defaultRoleConfig") RoleConfig defaultRoleConfig,
                       @Qualifier("adminRoleConfig") RoleConfig adminRoleConfig, ApplicationEventPublisher eventPublisher, CircuitBreakerFactory cbFactory
    ) {
        this.advancedWebClient = advancedWebClient;
        this.webClient = webClient;
        this.dbConfig = dbConfig;
        this.userRepo = userRepo;
        this.defaultConfig = defaultRoleConfig;
        this.adminRoleConfig = adminRoleConfig;
        this.eventPublisher = eventPublisher;
        this.cbFactory = cbFactory;
    }

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
