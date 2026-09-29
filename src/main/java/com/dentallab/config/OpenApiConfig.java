package com.dentallab.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3 文档：所有受保护接口通过 X-Auth-Token 请求头传递登录令牌。 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI dentalLabOpenAPI() {
        final String tokenHeader = "X-Auth-Token";
        return new OpenAPI()
                .info(new Info()
                        .title("牙科义齿加工订单取模返工与配送服务 API")
                        .version("1.0.0")
                        .description("义齿加工协同服务：订单/工序/异常/返工/色号争议/配送/试戴/结算/月结。"
                                + "先调用 POST /api/auth/login 获取 token，再点击右上角 Authorize 粘贴 token。"))
                .components(new Components().addSecuritySchemes(tokenHeader,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(tokenHeader)
                                .description("登录返回的 token，例如 8f3c...")))
                .addSecurityItem(new SecurityRequirement().addList(tokenHeader));
    }
}
