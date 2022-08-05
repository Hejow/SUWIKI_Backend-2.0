package usw.suwiki.domain.reportTarget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@Transactional
public interface EvaluateReportRepository extends JpaRepository<EvaluatePostReport, Long> {

    @Query(value = "SELECT * FROM evaluate_post_report", nativeQuery = true)
    List<EvaluatePostReport> loadAllReportedPosts();

    @Modifying
    void deleteByEvaluateIdx(Long evaluateIdx);

    @Modifying
    @Query(value = "DELETE FROM evaluate_posts where user_idx =:userIdx")
    void deleteByUserIdx(Long userIdx);

    Optional<EvaluatePostReport> findByEvaluateIdx(Long evaluateIdx);

}