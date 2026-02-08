package com.koreatech.indoor_pathfinding.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("실내 길찾기 API")
                .description("""
                    한국기술교육대학교 졸업작품 - 실내 길찾기 백엔드 API

                    ## 주요 기능
                    - 건물/층 관리
                    - 스캔 데이터 업로드 및 처리
                    - 경로 데이터 처리 파이프라인
                    - POI(관심지점) 관리
                    - 실내 길찾기 (A* 알고리즘)
                    - 수직 통로(계단/엘리베이터) 관리

                    ## API 문서 접근
                    | 서비스 | Swagger UI | OpenAPI JSON | ReDoc |
                    |--------|-----------|-------------|-------|
                    | Spring Boot (메인) | [/swagger-ui.html](/swagger-ui.html) | [/v3/api-docs](/v3/api-docs) | - |
                    | FastAPI (경로처리) | [localhost:8000/docs](http://localhost:8000/docs) | [localhost:8000/openapi.json](http://localhost:8000/openapi.json) | [localhost:8000/redoc](http://localhost:8000/redoc) |
                    """)
                .version("1.0.0")
                .contact(new Contact()
                    .name("KoreaTech Indoor Pathfinding Team")
                    .url("https://github.com/20HyeonsuLee/indoor-pathfinding-backend")))
            .tags(List.of(
                new Tag().name("Building").description("건물 관리 API"),
                new Tag().name("Floor").description("층 관리 API"),
                new Tag().name("Passage").description("수직 통로 API"),
                new Tag().name("Scan").description("스캔 데이터 업로드 API"),
                new Tag().name("Processing").description("경로 데이터 처리 API"),
                new Tag().name("POI").description("관심지점(POI) 관리 API"),
                new Tag().name("Pathfinding").description("실내 길찾기 API")
            ));
    }
}
