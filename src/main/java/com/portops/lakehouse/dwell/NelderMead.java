package com.portops.lakehouse.dwell;

/**
 * Nelder-Mead 单纯形优化（无导数），用于生存模型似然最大化。
 * 自包含实现，不引入外部数值库。
 */
final class NelderMead {

    private NelderMead() {
    }

    @FunctionalInterface
    interface Objective {
        double apply(double[] x);
    }

    record Result(double[] point, double value, int iterations, boolean converged) {
    }

    /**
     * 最小化目标函数。
     *
     * @param f       目标函数（返回 Double.NaN/Infinity 视为不可行区域）
     * @param start   初始点
     * @param step    各维度初始单纯形步长
     * @param maxIter 最大迭代次数
     * @param tol     收敛容差（单纯形顶点函数值与位置离散度）
     */
    static Result minimize(Objective f, double[] start, double[] step, int maxIter, double tol) {
        int n = start.length;
        double[][] simplex = new double[n + 1][];
        simplex[0] = start.clone();
        for (int i = 0; i < n; i++) {
            double[] p = start.clone();
            p[i] += step[i];
            simplex[i + 1] = p;
        }
        double[] values = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            values[i] = safe(f, simplex[i]);
        }

        final double alpha = 1.0, gamma = 2.0, rho = 0.5, sigma = 0.5;
        int iter = 0;
        boolean converged = false;
        while (iter < maxIter) {
            // 排序：values[0] 最优，values[n] 最差
            for (int i = 0; i <= n; i++) {
                for (int j = i + 1; j <= n; j++) {
                    if (values[j] < values[i]) {
                        double tv = values[i]; values[i] = values[j]; values[j] = tv;
                        double[] tp = simplex[i]; simplex[i] = simplex[j]; simplex[j] = tp;
                    }
                }
            }
            // 收敛判定：函数值与位置离散度均足够小
            double spreadV = Math.abs(values[n] - values[0]);
            double spreadX = 0;
            for (int i = 1; i <= n; i++) {
                for (int d = 0; d < n; d++) {
                    spreadX = Math.max(spreadX, Math.abs(simplex[i][d] - simplex[0][d]));
                }
            }
            if (spreadV < tol && spreadX < Math.sqrt(tol)) {
                converged = true;
                break;
            }
            iter++;

            // 质心（去掉最差点）
            double[] centroid = new double[n];
            for (int i = 0; i < n; i++) {
                for (int d = 0; d < n; d++) {
                    centroid[d] += simplex[i][d] / n;
                }
            }
            double[] worst = simplex[n];
            double[] reflected = new double[n];
            for (int d = 0; d < n; d++) {
                reflected[d] = centroid[d] + alpha * (centroid[d] - worst[d]);
            }
            double fReflected = safe(f, reflected);

            if (fReflected < values[0]) {
                // 扩展
                double[] expanded = new double[n];
                for (int d = 0; d < n; d++) {
                    expanded[d] = centroid[d] + gamma * (reflected[d] - centroid[d]);
                }
                double fExpanded = safe(f, expanded);
                if (fExpanded < fReflected) {
                    simplex[n] = expanded; values[n] = fExpanded;
                } else {
                    simplex[n] = reflected; values[n] = fReflected;
                }
            } else if (fReflected < values[n - 1]) {
                simplex[n] = reflected; values[n] = fReflected;
            } else {
                // 收缩
                double[] contracted = new double[n];
                if (fReflected < values[n]) {
                    for (int d = 0; d < n; d++) {
                        contracted[d] = centroid[d] + rho * (reflected[d] - centroid[d]);
                    }
                } else {
                    for (int d = 0; d < n; d++) {
                        contracted[d] = centroid[d] + rho * (worst[d] - centroid[d]);
                    }
                }
                double fContracted = safe(f, contracted);
                if (fContracted < Math.min(fReflected, values[n])) {
                    simplex[n] = contracted; values[n] = fContracted;
                } else {
                    // 压缩单纯形
                    for (int i = 1; i <= n; i++) {
                        for (int d = 0; d < n; d++) {
                            simplex[i][d] = simplex[0][d] + sigma * (simplex[i][d] - simplex[0][d]);
                        }
                        values[i] = safe(f, simplex[i]);
                    }
                }
            }
        }
        // 最终排序取最优
        int best = 0;
        for (int i = 1; i <= n; i++) {
            if (values[i] < values[best]) {
                best = i;
            }
        }
        return new Result(simplex[best], values[best], iter, converged);
    }

    private static double safe(Objective f, double[] x) {
        double v = f.apply(x);
        return Double.isFinite(v) ? v : 1e300;
    }
}
