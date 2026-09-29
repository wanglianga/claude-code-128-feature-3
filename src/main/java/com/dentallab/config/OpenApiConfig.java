package com.dentallab.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3 文档与 X-Auth-Token 鉴权头声明。 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI dentalLabOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("牙科义齿加工订单协同服务 API")
                        .version("1.0.0")
                        .description("订单、生产工序、色号争议处理、返工、配送、试戴、结算与月结。"
                                + "先调用 /api/auth/login 获取 token，在 Swagger Authorize 中填写后调用其他接口。"))
                .components(new Components().addSecuritySchemes("X-Auth-Token",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Auth-Token")))
                .addSecurityItem(new SecurityRequirement().addList("X-Auth-Token"));
    }
}
