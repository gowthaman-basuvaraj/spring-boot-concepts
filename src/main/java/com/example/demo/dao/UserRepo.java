package com.example.demo.dao;

import com.example.demo.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepo extends CrudRepository<User, Long> {
    List<User> findByPinCode(String pinCode);
    Optional<User> findByUserName(String userName);
    @Query("select u from User u where u.name ilike %?1%")
    List<User> searchByName(String name);
}
