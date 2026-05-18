package com.hopepms.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts a user's ACL cache the moment a change to their access commits.
 *
 * <p>{@code AFTER_COMMIT} is deliberate: the eviction must not happen until the
 * promote/demote/activate/deactivate/delete UPDATE is durable. Evicting before
 * commit would let a concurrent request repopulate the cache from the
 * pre-change row and pin the stale state right back in. {@code fallbackExecution
 * = true} keeps the eviction working even if a publisher is ever called outside
 * a transaction.
 */
@Component
public class UserAccessChangedListener {

    private static final Logger log = LoggerFactory.getLogger(UserAccessChangedListener.class);

    private final UserAccessService access;

    public UserAccessChangedListener(UserAccessService access) {
        this.access = access;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserAccessChanged(UserAccessChangedEvent event) {
        access.evict(event.userId());
        log.debug("ACL cache evicted for {} after access change", event.userId());
    }
}
