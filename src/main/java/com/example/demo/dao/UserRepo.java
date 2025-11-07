package com.example.demo.dao;

import com.example.demo.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepo extends JpaRepository<User, Long> {
    List<User> findByPinCode(String pinCode);

    Optional<User> findByUserName(String userName);

    @Query("select u from User u where u.name ilike %?1%")
    List<User> searchByName(String name);

    @Query("SELECT DISTINCT u FROM User u JOIN FETCH u.posts p JOIN FETCH p.comments c JOIN FETCH p.tags t")
    List<User> findAllWithPosts();

    @Query("SELECT u FROM User u JOIN FETCH u.posts WHERE u.id = :id")
    Optional<User> findByIdWithPosts(@Param("id") Long id);


    @EntityGraph(attributePaths = {"posts", "posts.comments"})
    @Query("SELECT u FROM User u")
    List<User> findAllWithPostsAndComments();

    @EntityGraph(
            type = EntityGraph.EntityGraphType.FETCH,
            attributePaths = {
                    "posts",
                    "posts.comments",
                    "posts.tags"
            }
    )
    @Query("SELECT u FROM User u WHERE u.active = true")
    List<User> findActiveUsersWithFullDetails();
}
