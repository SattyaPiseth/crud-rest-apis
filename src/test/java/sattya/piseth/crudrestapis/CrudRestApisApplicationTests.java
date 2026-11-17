package sattya.piseth.crudrestapis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.mockStatic;

class CrudRestApisApplicationTests {
    @Test
    void mainDelegatesToSpringApplication() {
        var args = new String[] {"--spring.main.web-application-type=none"};
        try (var springApplication = mockStatic(SpringApplication.class)) {
            CrudRestApisApplication.main(args);

            springApplication.verify(() -> SpringApplication.run(CrudRestApisApplication.class, args));
        }
    }
}
