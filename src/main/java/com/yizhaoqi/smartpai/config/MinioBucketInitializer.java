package com.yizhaoqi.smartpai.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioBucketInitializer {

    private static final Logger logger = LoggerFactory.getLogger(MinioBucketInitializer.class);

    @Bean
    public ApplicationRunner ensureMinioBucketExists(MinioClient minioClient,
                                                     @Value("${minio.bucketName:uploads}") String bucketName) {
        return args -> {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build()
            );
            if (exists) {
                logger.info("MinIO bucket 已存在: {}", bucketName);
                return;
            }

            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            logger.info("MinIO bucket 已创建: {}", bucketName);
        };
    }
}
