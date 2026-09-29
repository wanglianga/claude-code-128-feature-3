package com.dentallab.repo;

import com.dentallab.model.ShadeDispute;
import com.dentallab.model.ShadeDisputeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ShadeDisputeRepo extends JpaRepository<ShadeDispute, Long> {
    List<ShadeDispute> findByOrderIdOrderByIdAsc(Long orderId);

    List<ShadeDispute> findByToothItemIdOrderByIdAsc(Long toothItemId);

    List<ShadeDispute> findByToothItemIdAndStatusNot(Long toothItemId, ShadeDisputeStatus status);

    List<ShadeDispute> findByStatusOrderByIdDesc(ShadeDisputeStatus status);

    List<ShadeDispute> findByStatusInOrderByIdDesc(Collection<ShadeDisputeStatus> statuses);

    List<ShadeDispute> findByReworkId(Long reworkId);
}
