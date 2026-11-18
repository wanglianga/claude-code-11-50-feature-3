package com.portops.lakehouse.dwell;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生存模型数值正确性：Weibull MLE 在确定性合成数据上恢复真实参数；
 * 覆盖精确、区间删失、右删失与左截断（延迟进入）似然。
 */
class DwellModelAnalyzerTest {

    /** 确定性 Weibull 样本：t_i = λ·(-ln(1-u_i))^(1/k)，u_i = (i+0.5)/n。 */
    private static List<Double> weibullSample(double shape, double scale, int n) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double u = (i + 0.5) / n;
            out.add(scale * Math.pow(-Math.log(1.0 - u), 1.0 / shape));
        }
        return out;
    }

    @Test
    void weibullMleRecoversShapeAndScaleFromExactData() {
        List<DwellModelAnalyzer.ModelObs> data = weibullSample(1.5, 100.0, 400).stream()
                .map(t -> new DwellModelAnalyzer.ModelObs(t, t, 0.0))
                .toList();
        DwellModelAnalyzer.FittedModel fit = DwellModelAnalyzer.fitWeibull(data);
        assertThat(fit.converged()).isTrue();
        assertThat(fit.shape()).isBetween(1.35, 1.65);
        assertThat(fit.scale()).isBetween(90.0, 112.0);
    }

    @Test
    void weibullMleRecoversFromIntervalCensoredData() {
        // 精确时刻按 40h 宽度分组为区间删失（不取中点）
        List<DwellModelAnalyzer.ModelObs> data = weibullSample(1.5, 100.0, 400).stream()
                .map(t -> {
                    double l = Math.floor(t / 40.0) * 40.0;
                    return new DwellModelAnalyzer.ModelObs(l, l + 40.0, 0.0);
                })
                .toList();
        DwellModelAnalyzer.FittedModel fit = DwellModelAnalyzer.fitWeibull(data);
        assertThat(fit.converged()).isTrue();
        assertThat(fit.shape()).isBetween(1.2, 1.8);
        assertThat(fit.scale()).isBetween(80.0, 125.0);
    }

    @Test
    void weibullMleRecoversFromRightCensoredData() {
        // 120h 处右删失（约 30% 删失）
        List<DwellModelAnalyzer.ModelObs> data = weibullSample(1.5, 100.0, 400).stream()
                .map(t -> t > 120.0
                        ? new DwellModelAnalyzer.ModelObs(120.0, null, 0.0)
                        : new DwellModelAnalyzer.ModelObs(t, t, 0.0))
                .toList();
        DwellModelAnalyzer.FittedModel fit = DwellModelAnalyzer.fitWeibull(data);
        assertThat(fit.converged()).isTrue();
        assertThat(fit.shape()).isBetween(1.2, 1.8);
        assertThat(fit.scale()).isBetween(80.0, 125.0);
    }

    @Test
    void weibullMleHandlesLeftTruncation() {
        // 延迟进入：仅观测到存活超过 60h 的个体，按左截断条件似然恢复
        List<DwellModelAnalyzer.ModelObs> data = weibullSample(1.5, 100.0, 400).stream()
                .filter(t -> t > 60.0)
                .map(t -> new DwellModelAnalyzer.ModelObs(t, t, 60.0))
                .toList();
        DwellModelAnalyzer.FittedModel fit = DwellModelAnalyzer.fitWeibull(data);
        assertThat(fit.shape()).isBetween(1.1, 1.9);
        assertThat(fit.scale()).isBetween(75.0, 135.0);
    }

    @Test
    void nelderMeadMinimizesQuadratic() {
        NelderMead.Result r = NelderMead.minimize(
                x -> (x[0] - 3.0) * (x[0] - 3.0) + (x[1] + 1.0) * (x[1] + 1.0),
                new double[]{0.0, 0.0}, new double[]{1.0, 1.0}, 1000, 1e-10);
        assertThat(r.point()[0]).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(r.point()[1]).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-3));
    }
}
