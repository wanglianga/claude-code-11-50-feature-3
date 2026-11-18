package com.portops.lakehouse.common;

/** 事件在事件账中的处理状态。 */
public enum EventStatus {
    /** 已入账，待提交 */
    RECEIVED,
    /** 已包含在某个不可变提交中 */
    COMMITTED,
    /** 与其他来源冲突，待裁定 */
    PENDING_ARBITRATION,
    /** 裁定后被取代（保留在账上，不再生效） */
    SUPERSEDED
}
