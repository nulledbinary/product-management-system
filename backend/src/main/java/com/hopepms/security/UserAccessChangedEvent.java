package com.hopepms.security;

/**
 * Published whenever an account's authorization-relevant state changes —
 * role tier (promote/demote), record status (activate/deactivate), the
 * fine-grained rights grant, or the row itself (create/hard-delete).
 *
 * <p>{@link UserAccessChangedListener} consumes it after the surrounding
 * transaction commits and evicts that user's ACL cache, so the change is
 * observed on the target's next request rather than at session expiry.
 */
public record UserAccessChangedEvent(String userId) {
}
