package org.pgledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.DefectId;
import org.springframework.data.repository.Repository;

/** Fix commits of defects: insert, update of the CI data, read. No delete. */
public interface DefectFixRepository extends Repository<DefectFix, Long>, Insertable<DefectFix>,
        Updatable<DefectFix> {

    Optional<DefectFix> findById(Long id);

    List<DefectFix> findByDefectIdOrderById(DefectId defectId);

    Optional<DefectFix> findByDefectIdAndRepoAndCommitSha(DefectId defectId, String repo, CommitSha commitSha);
}
