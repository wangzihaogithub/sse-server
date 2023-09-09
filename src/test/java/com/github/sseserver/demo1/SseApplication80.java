package com.github.sseserver.demo1;

//import com.github.netty.springboot.EnableNettyEmbedded;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Websocket服务端 端口号:10005
 * 访问 http://localhost:10005/index.html 可以看效果
 *
 * @author wangzihao
 */
//@EnableNettyEmbedded
@SpringBootApplication
@ServletComponentScan
public class SseApplication80 {

    public static void main(String[] args) {
        SpringApplication.run(SseApplication80.class, args);
    }

//    @Bean
//    public RedisConnectionFactory redisConnectionFactory(RedisProperties redisProperties) {
//        RedisClusterConfiguration configuration = new RedisClusterConfiguration(redisProperties.getCluster().getNodes());
//        configuration.setPassword(redisProperties.getPassword());
//        return new LettuceConnectionFactory(configuration);
//    }

}
