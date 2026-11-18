package com.portops.lakehouse.dwell;

import java.util.List;

/**
 * 路线二：参数生存模型（Weibull，含指数分布参照）。
 * 同一似然框架同时处理：延迟进入（左截断条件似然）、右删失 S(c)、
 * 区间删失 S(L)−S(R)、精确事件密度 f(t)；不把区间中点当作精确时刻。
 */
final class DwellModelAnalyzer {

    private DwellModelAnalyzer() {
    }

    /** 模型观测：滞留时长区间 (lower, upper]（upper=null 为右删失/上界无穷），
     *  truncationHours 为左截断时间（延迟进入；0 表示无截断）。 */
    record ModelObs(double lower, Double upper, double truncationHours) {
    }

    /** 拟合结果。 */
    record FittedModel(String model, double shape, double scale, double logLik,
                       int n, boolean converged) {
        /** 生存函数 S(t)。 */
        double survival(double t) {
            return Math.exp(-hazardPow(t, shape, Math.log(scale)));
        }

        /** 分位数 Q(q)。 */
        double quantile(double q) {
            return scale * Math.pow(-Math.log(1.0 - q), 1.0 / shape);
        }
    }

    /** Weibull 拟合（形状 k 与尺度 λ 联合 MLE，Nelder-Mead 双起点取优）。 */
    static FittedModel fitWeibull(List<ModelObs> data) {
        double meanMid = meanMidpoint(data);
        double logLam0 = Math.log(Math.max(meanMid, 1e-6));
        NelderMead.Objective negLL = x -> -logLik(data, x[0], x[1]);
        NelderMead.Result r1 = NelderMead.minimize(negLL,
                new double[]{0.0, logLam0}, new double[]{0.3, 0.5}, 2000, 1e-9);
        NelderMead.Result r2 = NelderMead.minimize(negLL,
                new double[]{0.7, logLam0 + 0.5}, new double[]{0.3, 0.5}, 2000, 1e-9);
        NelderMead.Result best = r1.value() <= r2.value() ? r1 : r2;
        double shape = Math.exp(best.point()[0]);
        double scale = Math.exp(best.point()[1]);
        boolean converged = best.converged() && Double.isFinite(shape) && Double.isFinite(scale)
                && shape > 0 && shape < 100;
        return new FittedModel("Weibull", shape, scale, -best.value(), data.size(), converged);
    }

    /** 指数分布参照拟合（形状固定 1，尺度一维黄金分割搜索），用于分布假设敏感性。 */
    static FittedModel fitExponential(List<ModelObs> data) {
        double meanMid = meanMidpoint(data);
        double lo = Math.log(Math.max(meanMid, 1e-6)) - 8;
        double hi = Math.log(Math.max(meanMid, 1e-6)) + 8;
        double gr = (Math.sqrt(5) - 1) / 2;
        double c = hi - gr * (hi - lo);
        double d = lo + gr * (hi - lo);
        java.util.function.DoubleUnaryOperator negLL = logLam -> -logLik(data, 0.0, logLam);
        double fc = negLL.applyAsDouble(c);
        double fd = negLL.applyAsDouble(d);
        for (int i = 0; i < 300; i++) {
            if (fc < fd) {
                hi = d; d = c; fd = fc; c = hi - gr * (hi - lo); fc = negLL.applyAsDouble(c);
            } else {
                lo = c; c = d; fc = fd; d = lo + gr * (hi - lo); fd = negLL.applyAsDouble(d);
            }
        }
        double logLam = (lo + hi) / 2;
        return new FittedModel("Exponential", 1.0, Math.exp(logLam),
                logLik(data, 0.0, logLam), data.size(), true);
    }

    /** 区间删失/右删失/精确/左截断混合似然（对数）。logK=ln k，logLam=ln λ。 */
    static double logLik(List<ModelObs> data, double logK, double logLam) {
        double k = Math.exp(logK);
        double ll = 0.0;
        for (ModelObs obs : data) {
            if (obs.upper() != null && Math.abs(obs.upper() - obs.lower()) < 1e-9) {
                // 精确事件：密度 f(t)
                double t = Math.max(obs.lower(), 1e-9);
                double hp = hazardPow(t, k, logLam);
                if (!Double.isFinite(hp)) {
                    return Double.NEGATIVE_INFINITY;
                }
                ll += logK - logLam + (k - 1) * (Math.log(t) - logLam) - hp;
            } else {
                // 区间 (l, r]：S(l) − S(r)；r=∞ 即右删失 S(l)
                double a = obs.lower() <= 0 ? 0.0 : hazardPow(obs.lower(), k, logLam);
                double b = obs.upper() == null
                        ? Double.POSITIVE_INFINITY : hazardPow(obs.upper(), k, logLam);
                if (Double.isInfinite(a)) {
                    return Double.NEGATIVE_INFINITY;
                }
                if (Double.isInfinite(b)) {
                    ll += -a;
                } else {
                    double diff = b - a;
                    if (diff <= 1e-12) {
                        return Double.NEGATIVE_INFINITY;
                    }
                    ll += -a + Math.log1p(-Math.exp(-diff));
                }
            }
            // 左截断（延迟进入）：条件似然除以 S(L0)
            if (obs.truncationHours() > 0) {
                double h0 = hazardPow(obs.truncationHours(), k, logLam);
                if (!Double.isFinite(h0)) {
                    return Double.NEGATIVE_INFINITY;
                }
                ll += h0;
            }
        }
        return ll;
    }

    /** 累积风险 (t/λ)^k = exp(k·(ln t − ln λ))，溢出保护。 */
    private static double hazardPow(double t, double k, double logLam) {
        if (t <= 0) {
            return 0.0;
        }
        double z = k * (Math.log(t) - logLam);
        if (z > 700) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.exp(z);
    }

    private static double meanMidpoint(List<ModelObs> data) {
        double sum = 0;
        for (ModelObs o : data) {
            if (o.upper() == null) {
                sum += o.lower() * 1.5 + 1;
            } else {
                sum += (o.lower() + o.upper()) / 2;
            }
        }
        return sum / data.size();
    }
}
