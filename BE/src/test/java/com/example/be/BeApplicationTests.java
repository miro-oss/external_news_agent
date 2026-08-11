package com.example.be;

import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
class BeApplicationTests {

    // DataSource 자동설정을 제외하면 JPA 리포지터리 빈도 만들어지지 않으므로 대역으로 채운다.
    @MockitoBean
    private TopicRepository topicRepository;

    @MockitoBean
    private SourceRepository sourceRepository;

    @Test
    void contextLoads() {
    }

}
