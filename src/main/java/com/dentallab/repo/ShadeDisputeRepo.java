package com.dentallab.repo;

import com.dentallab.model.ShadeDispute;
import com.dentallab.model.ShadeDisputeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShadeDisputeRepo extends JpaRepository<ShadeDispute, Long> {
    List<ShadeDispute> findByOrderIdOrderByIdAsc(Long orderId);

    List<ShadeDispute> findByToothItemIdOrderByIdAsc(Long toothItemId);

    Optional<ShadeDispute> findByToothItemIdAndStatus(Long toothItemId, ShadeDisputeStatus status);

    List<ShadeDispute> findByStatusOrderByIdDesc(ShadeDisputeStatus status);

    /** 同牙位存在未归档争议时不能重复发起。 */
    boolean existsByToothItemIdAndStatusIn(Long toothItemId, List<ShadeDisputeStatus> statuses);
}
