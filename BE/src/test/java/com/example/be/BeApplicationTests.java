package com.example.be;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

/**
 * 애플리케이션 컨텍스트가 뜨는지만 본다. 빈 배선이 깨지면 여기서 먼저 걸린다.
 *
 * <p>CI에는 {@code .env}가 없어 실제 DataSource를 만들 수 없다. 예전에는 그래서 DataSource 자동설정을
 * 통째로 제외했는데, <b>그러면 JPA 리포지터리 빈도 안 만들어져</b> 리포지터리를 참조하는 서비스마다
 * {@code @MockitoBean}을 하나씩 더해야 컨텍스트가 떴다. M3에서만 세 번 깨졌고 대역이 7개까지 늘었다.
 *
 * <p>이제 <b>연결하지 않는 DataSource 하나만</b> 끼운다. 리포지터리는 정상적으로 만들어지므로
 * 리포지터리를 추가해도 이 테스트는 깨지지 않는다. 대신 Hibernate가 기동 중에 DB를 만지지 않도록
 * 방언을 직접 지정하고 JDBC 메타데이터 조회를 끈다 — 안 그러면 방언을 알아내려고 커넥션을 연다.
 *
 * <p>스키마 검증({@code ddl-auto=validate})과 Flyway는 실제 DB가 있어야 의미가 있어 여기서는 끈다.
 * 그건 {@code -Dnews.integration.db=true}로 도는 통합 테스트가 본다.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
@TestPropertySource(properties = {
        // 기동 시 유실 실행 정리는 실제 쿼리를 날린다. DB가 없는 이 테스트에서는 끈다.
        "news.collection.reap-on-startup=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database=ORACLE",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.OracleDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false"
})
class BeApplicationTests {

    @Test
    void contextLoads() {
    }

    @TestConfiguration
    static class NoConnectionDataSourceConfig {

        /**
         * 리포지터리 빈을 만들기 위한 자리만 채운다. 실제로 커넥션을 열면 그건 이 테스트가
         * 하려던 일이 아니므로, 대역이 그대로 실패해 드러나는 편이 낫다.
         */
        @Bean
        DataSource dataSource() {
            return Mockito.mock(DataSource.class);
        }
    }
}
