package com.portops.lakehouse.common;

/** 作业事件类型。 */
public enum EventType {
    /** 闸口放行 */
    GATE_RELEASE,
    /** 场桥移动 */
    CRANE_MOVE,
    /** 堆场盘点 */
    YARD_CHECK,
    /** 装船确认 */
    LOAD_CONFIRM,
    /** 更正报文（含截关后补发） */
    CORRECTION
}
