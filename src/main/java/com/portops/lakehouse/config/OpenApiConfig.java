package com.portops.lakehouse.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI portLakehouseOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("港口集装箱作业事件湖仓与航次对账服务")
                        .version("1.0.0")
                        .description("""
                                事件账 + 不可变提交清单架构：
                                - 接入服务保存来源事件及顺序标识（闸口/场桥/盘点/装船）；
                                - 湖仓写入服务按箱号+作业分区生成不可变提交，冲突进入待裁定；
                                - 航次清单服务从已提交数据冻结截关快照，只引用完整提交清单；
                                - 对账服务将快照与承运人回执逐项比较，差异生成费用调整单；
                                - 调度查询读取当前提交合并视图；结算只能引用已冻结航次版本；
                                - 滞留分析覆盖期初在场（延迟进入）、期末仍在场（右删失）与区间盘点证据
                                  （区间删失）：路线一只发布证据支持的可行界，路线二为 Weibull 生存模型
                                  并披露假设与敏感性；在场风险区间、统计估计与管理报告分别版本化。
                                """))
                .components(new Components().addSecuritySchemes("basicAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
