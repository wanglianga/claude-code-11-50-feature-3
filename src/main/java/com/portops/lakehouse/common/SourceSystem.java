package com.portops.lakehouse.common;

/** 上报来源系统。每个来源系统维护独立的来源序号空间。 */
public enum SourceSystem {
    /** 闸口系统 */
    GATE,
    /** 场桥系统 */
    CRANE,
    /** 堆场盘点系统 */
    YARD,
    /** 装船确认系统 */
    VESSEL
}
