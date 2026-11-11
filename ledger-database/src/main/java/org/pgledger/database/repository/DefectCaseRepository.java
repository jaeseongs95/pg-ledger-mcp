package org.pgledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.pgledger.domain.model.DefectCase;
import org.pgledger.domain.value.DefectId;
import org.springframework.data.repository.Repository;

/** Observed cases of defects: insert and read. No update, no delete. */
public interface DefectCaseRepository extends Repository<DefectCase, Long>, Insertable<DefectCase> {

    Optional<DefectCase> findById(Long id);

    List<DefectCase> findByDefectIdOrderById(DefectId defectId);
}
