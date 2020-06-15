package ru.hostco.dvs.gitlabtelegramintegration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
public class GitlabTelegramIntegrationApplication {

	public static void main(String[] args) {
		SpringApplication.run(GitlabTelegramIntegrationApplication.class, args);
	}

}
