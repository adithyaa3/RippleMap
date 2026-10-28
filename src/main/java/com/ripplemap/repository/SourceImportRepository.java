package com.ripplemap.repository;

import com.ripplemap.entity.SourceImport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SourceImportRepository extends JpaRepository<SourceImport, Long> {

    List<SourceImport> findByProjectIdOrderByVersionNumberDesc(Long projectId);

    Optional<SourceImport> findFirstByProjectIdOrderByVersionNumberDesc(Long projectId);

    Optional<SourceImport> findByIdAndProjectId(Long id, Long projectId);

    long countByProjectId(Long projectId);

    @Query("select coalesce(max(i.versionNumber), 0) from SourceImport i where i.project.id = :projectId")
    int maxVersionNumber(@Param("projectId") Long projectId);

    @Query("select coalesce(sum(i.extractedBytes), 0) from SourceImport i")
    long totalExtractedBytes();

    @Query("select i.storageDir from SourceImport i")
    List<String> findAllStorageDirs();

    @Query("select i.storageDir from SourceImport i where i.project.id = :projectId")
    List<String> findStorageDirsByProject(@Param("projectId") Long projectId);
}
