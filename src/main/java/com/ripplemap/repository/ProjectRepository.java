package com.ripplemap.repository;

import com.ripplemap.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByNameKey(String nameKey);

    boolean existsByNameKey(String nameKey);

    List<Project> findAllByOrderByUpdatedAtDesc();
}
