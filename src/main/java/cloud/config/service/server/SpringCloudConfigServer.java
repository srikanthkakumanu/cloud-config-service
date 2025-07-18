package cloud.config.service.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@SpringBootApplication
@EnableConfigServer
@EnableDiscoveryClient
public class SpringCloudConfigServer {

	public static void main(String[] args) {
		SpringApplication.run(SpringCloudConfigServer.class, args);
	}

}
