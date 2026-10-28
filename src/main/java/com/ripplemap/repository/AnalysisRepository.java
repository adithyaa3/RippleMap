package com.ripplemap.repository;

import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

    @EntityGraph(attributePaths = {"sourceImport"})
    List<Analysis> findByProjectIdOrderBySequenceNumberDesc(Long projectId);

    @EntityGraph(attributePaths = {"sourceImport", "project"})
    Optional<Analysis> findByIdAndProjectId(Long id, Long projectId);

    @EntityGraph(attributePaths = {"sourceImport"})
    Optional<Analysis> findFirstByProjectIdAndStatusInOrderBySequenceNumberDesc(Long projectId,
                                                                               Collection<AnalysisStatus> statuses);

    @EntityGraph(attributePaths = {"sourceImport"})
    Optional<Analysis> findFirstByProjectIdOrderBySequenceNumberDesc(Long projectId);

    boolean existsByProjectIdAndStatusIn(Long projectId, Collection<AnalysisStatus> statuses);

    long countByProjectId(Long projectId);

    List<Analysis> findByStatusIn(Collection<AnalysisStatus> statuses);

    @Query("select coalesce(max(a.sequenceNumber), 0) from Analysis a where a.project.id = :projectId")
    int maxSequenceNumber(@Param("projectId") Long projectId);

    @EntityGraph(attributePaths = {"project", "sourceImport"})
    @Query("select a from Analysis a order by a.createdAt desc, a.id desc")
    List<Analysis> findRecent(Pageable pageable);

    @EntityGraph(attributePaths = {"project"})
    @Query("""
            select a from Analysis a
            where a.status in :statuses and a.sequenceNumber = (
                select max(b.sequenceNumber) from Analysis b
                where b.project.id = a.project.id and b.status in :statuses)
            """)
    List<Analysis> findLatestPerProjectWithStatus(@Param("statuses") Collection<AnalysisStatus> statuses);
}
