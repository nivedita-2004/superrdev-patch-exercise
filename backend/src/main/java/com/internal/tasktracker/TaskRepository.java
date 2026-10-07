package com.internal.tasktracker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    @Query(value = "SELECT t FROM Task t WHERE t.archived = false "
                 + "AND (:term = '' OR LOWER(t.title) LIKE LOWER(CONCAT('%', :term, '%')) "
                 + "OR LOWER(COALESCE(t.description, '')) LIKE LOWER(CONCAT('%', :term, '%'))) "
                 + "AND (:status IS NULL OR t.status = :status)",
           countQuery = "SELECT COUNT(t) FROM Task t WHERE t.archived = false "
                 + "AND (:term = '' OR LOWER(t.title) LIKE LOWER(CONCAT('%', :term, '%')) "
                 + "OR LOWER(COALESCE(t.description, '')) LIKE LOWER(CONCAT('%', :term, '%'))) "
                 + "AND (:status IS NULL OR t.status = :status)")
    Page<Task> searchTasks(@Param("term") String term, @Param("status") String status, Pageable pageable);
}
