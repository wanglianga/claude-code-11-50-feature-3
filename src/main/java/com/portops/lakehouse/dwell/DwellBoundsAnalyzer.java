package com.portops.lakehouse.dwell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 路线一：非参数可行界分析。
 * 每只箱的滞留时长只取证据允许的区间 [lower, upper]（upper 可为 +∞），
 * 不猜测区间内具体时点、不假定删失分布；分组超期比例与分位数只发布可行上下界。
 */
final class DwellBoundsAnalyzer {

    private DwellBoundsAnalyzer() {
    }

    /** 区间观测：upper 为 null 表示 +∞。 */
    record Interval(double lower, Double upper) {
    }

    /** 超期比例可行界 [lower, upper]。 */
    static double[] overdueProportionBounds(List<Interval> data, double thresholdHours) {
        int definite = 0;
        int possible = 0;
        for (Interval iv : data) {
            if (iv.lower() > thresholdHours) {
                definite++;
            }
            if (iv.upper() == null || iv.upper() > thresholdHours) {
                possible++;
            }
        }
        int n = data.size();
        return new double[]{definite / (double) n, possible / (double) n, definite, possible};
    }

    /**
     * 分位数可识别范围 [lower, upper]；upper 为 null 表示该分位点上界不可识别
     * （删失/区间证据过多，任何有限上界都与证据相容）。
     */
    static Double[] quantileBounds(List<Interval> data, double q) {
        int n = data.size();
        int k = (int) Math.ceil(q * n);
        List<Double> lowers = new ArrayList<>();
        List<Double> uppers = new ArrayList<>();
        for (Interval iv : data) {
            lowers.add(iv.lower());
            uppers.add(iv.upper() == null ? Double.POSITIVE_INFINITY : iv.upper());
        }
        Collections.sort(lowers);
        Collections.sort(uppers);
        Double lower = lowers.get(k - 1);
        Double upper = uppers.get(k - 1);
        return new Double[]{lower, Double.isInfinite(upper) ? null : upper};
    }
}
