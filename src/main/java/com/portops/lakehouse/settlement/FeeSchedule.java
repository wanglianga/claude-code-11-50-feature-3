package com.portops.lakehouse.settlement;

import com.portops.lakehouse.common.ContainerState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 费用表：由截关快照中的可收费装卸事实计算费用。
 * 装船费 450.00/箱；场桥移箱费 80.00/次；闸口放行费 60.00/次。
 */
public final class FeeSchedule {

    public static final BigDecimal LOAD_FEE = new BigDecimal("450.00");
    public static final BigDecimal MOVE_FEE = new BigDecimal("80.00");
    public static final BigDecimal GATE_FEE = new BigDecimal("60.00");

    private FeeSchedule() {
    }

    public static List<Map<String, Object>> computeCharges(ContainerState s) {
        List<Map<String, Object>> charges = new ArrayList<>();
        if (s.loaded) {
            charges.add(line("LOAD_FEE", 1, LOAD_FEE));
        }
        if (s.moveCount > 0) {
            charges.add(line("MOVE_FEE", s.moveCount, MOVE_FEE));
        }
        if (s.gateReleased) {
            charges.add(line("GATE_FEE", 1, GATE_FEE));
        }
        return charges;
    }

    private static Map<String, Object> line(String feeType, int qty, BigDecimal unitPrice) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("feeType", feeType);
        m.put("quantity", qty);
        m.put("unitPrice", unitPrice);
        m.put("amount", unitPrice.multiply(BigDecimal.valueOf(qty)));
        return m;
    }
}
