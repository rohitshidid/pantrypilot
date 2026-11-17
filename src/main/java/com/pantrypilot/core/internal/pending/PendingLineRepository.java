package com.pantrypilot.core.internal.pending;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingLineRepository extends JpaRepository<PendingLine, Long> {

    List<PendingLine> findByPendingChangeIdOrderByLineNo(long pendingChangeId);

    List<PendingLine> findByPendingChangeIdInOrderByLineNo(Collection<Long> pendingChangeIds);

    void deleteByPendingChangeId(long pendingChangeId);
}
